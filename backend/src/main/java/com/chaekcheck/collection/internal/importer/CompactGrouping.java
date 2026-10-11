package com.chaekcheck.collection.internal.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.catalog.normalize.PublishedDates;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * The grouping {@code compact} (cycle 11, ADR-0079, spec R-B2 decision 9): the same books, ids and report counts as
 * {@link BookGrouping}, with less heap. The steps and their tie rules are the ones of {@link BookGrouping}; only the
 * way the counts are kept differs.
 * <ul>
 * <li>No object per step-① unit: every value is a column indexed by the unit number ({@link IntColumn} and friends,
 * pages of 16K entries, so growing never copies a large array).</li>
 * <li>Per field (title, author, publisher, content type, ISBN, date) a unit keeps its first value and that value's
 * row count. Titles, authors and publishers are stored once each in a {@link StringPool} and kept as int ids; the
 * ISBN as a long ({@link #encodeIsbn}), the date as its epoch day, the content type as a byte. A unit's other values
 * (second distinct value on) are nodes of one chain per unit, shared by all fields.</li>
 * <li>The libraries of a unit: the first inline, the others as a chain of int nodes.</li>
 * <li>The unit key {@link BookGrouper#unitKey} is not kept: a {@link KeyTable} keeps two 64-bit hashes of it (128
 * bits) and finds the unit by them. Two keys with the same 128 bits are treated as impossible: for 10 million keys the
 * chance is about n²/2¹²⁹ ≈ 10⁻²⁵.</li>
 * <li>Step ④'s keys are kept the same way, and the order of the wide-date samples, which {@link BookGrouping} takes
 * from a {@link HashMap}, is worked out from the keys' {@link String#hashCode} ({@link MapShape}).</li>
 * </ul>
 * Rough size per unit: 16 bytes of key hash, about 6 of key table slots, 49 of first values and counts, 8 of library
 * heads, then 12 more for the union-find and the group ISBN, against several hundred bytes of objects in
 * {@link BookGrouping}. {@link #forEachBook} may be called once: it frees everything but what {@link #bookOf} needs.
 */
final class CompactGrouping implements BookGrouper {

	private static final int TITLE = 0;

	private static final int AUTHOR = 1;

	private static final int PUBLISHER = 2;

	private static final int TYPE = 3;

	private static final int ISBN = 4;

	private static final int DATE = 5;

	private static final int FIELDS = 6;

	/** Step ④ samples kept in the report, as in {@link BookGrouping#mergeSameText}. */
	private static final int WIDE_DATE_SAMPLES = 40;

	/** Entries per page of the columns. */
	private static final int PAGE_BITS = 14;

	private static final int PAGE = 1 << PAGE_BITS;

	private static final int PAGE_MASK = PAGE - 1;

	private final KeyTable units;

	private int unitCount;

	/** Always rebuild the wide-date sample order with a real {@link HashMap} (tests of that path). */
	private final boolean replaySampleOrder;

	private StringPool titles = new StringPool();

	private StringPool authors = new StringPool();

	private StringPool publishers = new StringPool();

	/** Content types by byte id; few distinct values. */
	private List<String> typeNames = new ArrayList<>();

	private Map<String, Integer> typeIds = new HashMap<>();

	/** ISBN texts that are not 13 digits (only possible from step ③'s map), by {@code -code - 1}. */
	private List<String> oddIsbns = new ArrayList<>();

	private Map<String, Integer> oddIsbnIds = new HashMap<>();

	/** Per field and unit: rows of the first value; 0 when the unit has no value for the field. */
	private IntColumn[] firstCounts = new IntColumn[FIELDS];

	/** First values: string pool ids for title, author, publisher; epoch day for the date. */
	private IntColumn[] firstInts = new IntColumn[FIELDS];

	private ByteColumn firstType = new ByteColumn();

	private LongColumn firstIsbn = new LongColumn();

	/** Per unit: the first node (+1) of its chain of other values; 0 when none. */
	private IntColumn moreHead = new IntColumn();

	private IntColumn moreNext = new IntColumn();

	private ByteColumn moreField = new ByteColumn();

	private LongColumn moreValue = new LongColumn();

	private IntColumn moreCount = new IntColumn();

	private int moreSize;

	/** Per unit: the service id of its first row's library, and the first node (+1) of the other libraries. */
	private IntColumn libraryFirst = new IntColumn();

	private IntColumn libraryHead = new IntColumn();

	private IntColumn libraryNodeId = new IntColumn();

	private IntColumn libraryNodeNext = new IntColumn();

	private int librarySize;

	private int[] parent;

	/** ISBN code of a group ({@link #encodeIsbn}), kept on the group root; 0 for none. */
	private long[] isbnOf;

	/** The id the book of each step-① unit got, set by {@link #forEachBook}. */
	private long[] bookOfUnit;

	private final Tally tally = new Tally();

	CompactGrouping() {
		this(1 << 10, false);
	}

	/** For tests: a small first key table, and the real {@link HashMap} for the sample order. */
	CompactGrouping(int unitTableCapacity, boolean replaySampleOrder) {
		this.units = new KeyTable(unitTableCapacity);
		this.replaySampleOrder = replaySampleOrder;
		for (int field : new int[] { TITLE, AUTHOR, PUBLISHER, DATE }) {
			firstInts[field] = new IntColumn();
		}
		for (int field = 0; field < FIELDS; field++) {
			firstCounts[field] = new IntColumn();
		}
	}

	/** First pass: counts one row in its step-① unit. */
	@Override
	public void add(Row row, ImportReport report) {
		int library = Math.toIntExact(row.library().serviceId());
		int unit = units.add(BookGrouper.unitKey(row));
		if (unit == unitCount) {
			unitCount++;
			libraryFirst.set(unit, library);
		}
		else {
			int node = librarySize++;
			libraryNodeId.set(node, library);
			libraryNodeNext.set(node, libraryHead.get(unit));
			libraryHead.set(unit, node + 1);
		}
		voteText(TITLE, titles, unit, row.title());
		voteText(AUTHOR, authors, unit, row.authorRaw());
		voteText(PUBLISHER, publishers, unit, row.publisher());
		String type = row.contentType();
		if (type != null && !type.isBlank()) {
			vote(TYPE, unit, typeId(type));
		}
		if (row.publishedOn() != null) {
			vote(DATE, unit, Math.toIntExact(row.publishedOn().toEpochDay()));
		}
		String isbn = BookGrouper.kyoboIsbn(row, report);
		if (isbn != null) {
			vote(ISBN, unit, encodeIsbn(isbn));
		}
	}

	private void voteText(int field, StringPool pool, int unit, String value) {
		if (value != null && !value.isBlank()) {
			vote(field, unit, pool.idOf(value));
		}
	}

	/** Counts one row's value: the first value inline, the others in the unit's chain. */
	private void vote(int field, int unit, long value) {
		int count = firstCounts[field].get(unit);
		if (count == 0) {
			setFirstValue(field, unit, value);
			firstCounts[field].set(unit, 1);
			return;
		}
		if (firstValue(field, unit) == value) {
			firstCounts[field].set(unit, count + 1);
			return;
		}
		for (int node = moreHead.get(unit); node != 0; node = moreNext.get(node - 1)) {
			int i = node - 1;
			if (moreField.get(i) == field && moreValue.get(i) == value) {
				moreCount.set(i, moreCount.get(i) + 1);
				return;
			}
		}
		int i = moreSize++;
		moreField.set(i, (byte) field);
		moreValue.set(i, value);
		moreCount.set(i, 1);
		moreNext.set(i, moreHead.get(unit));
		moreHead.set(unit, i + 1);
	}

	private long firstValue(int field, int unit) {
		return switch (field) {
			case TYPE -> firstType.get(unit) & 0xFF;
			case ISBN -> firstIsbn.get(unit);
			default -> firstInts[field].get(unit);
		};
	}

	private void setFirstValue(int field, int unit, long value) {
		switch (field) {
			case TYPE -> firstType.set(unit, (byte) value);
			case ISBN -> firstIsbn.set(unit, value);
			default -> firstInts[field].set(unit, (int) value);
		}
	}

	private int typeId(String type) {
		Integer id = typeIds.get(type);
		if (id == null) {
			if (typeNames.size() == 256) {
				throw new IllegalStateException("More than 256 content types; the compact grouping keeps one byte");
			}
			id = typeNames.size();
			typeNames.add(type);
			typeIds.put(type, id);
		}
		return id;
	}

	/**
	 * ISBN text as a long: 13 ASCII digits give their number (positive; with equal lengths the number order is the
	 * text order), anything else a negative code into {@link #oddIsbns}.
	 */
	private long encodeIsbn(String isbn) {
		if (isbn.length() == 13) {
			long number = 0;
			boolean digits = true;
			for (int i = 0; i < 13 && digits; i++) {
				char c = isbn.charAt(i);
				digits = c >= '0' && c <= '9';
				number = number * 10 + (c - '0');
			}
			if (digits && number > 0) {
				return number;
			}
		}
		Integer id = oddIsbnIds.get(isbn);
		if (id == null) {
			id = oddIsbns.size();
			oddIsbns.add(isbn);
			oddIsbnIds.put(isbn, id);
		}
		return -id - 1L;
	}

	private String decodeIsbn(long code) {
		if (code < 0) {
			return oddIsbns.get((int) (-code - 1));
		}
		String digits = Long.toString(code);
		return digits.length() == 13 ? digits : "0".repeat(13 - digits.length()) + digits;
	}

	private int compareIsbn(long a, long b) {
		return a > 0 && b > 0 ? Long.compare(a, b) : decodeIsbn(a).compareTo(decodeIsbn(b));
	}

	@Override
	public long bookOf(Row row) {
		String key = BookGrouper.unitKey(row);
		int unit = units.find(key);
		if (unit < 0) {
			throw new IllegalStateException("Row not seen in the first pass: " + key);
		}
		return bookOfUnit[unit];
	}

	/** Steps ① (already the units) and ②. */
	@Override
	public void groupByCodes(ImportReport report) {
		int n = unitCount;
		parent = new int[n];
		for (int i = 0; i < n; i++) {
			parent[i] = i;
		}
		isbnOf = new long[n];
		report.booksAfterPlatformCode = n;

		LongIntMap byIsbn = new LongIntMap();
		for (int unit = 0; unit < n; unit++) {
			if (firstCounts[ISBN].get(unit) > 0) {
				unionWithFirst(byIsbn, firstIsbn.get(unit), unit);
			}
			for (int node = moreHead.get(unit); node != 0; node = moreNext.get(node - 1)) {
				if (moreField.get(node - 1) == ISBN) {
					unionWithFirst(byIsbn, moreValue.get(node - 1), unit);
				}
			}
		}
		byIsbn = null;
		// the ISBN of a group: the one most of its rows computed
		Groups groups = groups();
		for (int root = 0; root < n; root++) {
			if (groups.count[root] > 0) {
				long isbn = mostCommon(ISBN, groups.members, groups.start[root], groups.end(root));
				if (isbn != 0) {
					isbnOf[find(root)] = isbn;
				}
			}
		}
		report.booksAfterKyoboIsbn = groupCount();
	}

	@Override
	public Set<String> bibliographyKeys() {
		Set<String> keys = new HashSet<>();
		Groups groups = groups();
		for (int root = 0; root < unitCount; root++) {
			if (groups.count[root] > 0 && isbnOf[find(root)] == 0) {
				String key = keyOf(groups.members, groups.start[root], groups.end(root));
				if (key != null) {
					keys.add(key);
				}
			}
		}
		return keys;
	}

	/** Step ③, as {@link BookGrouping#attachBibliography}. */
	@Override
	public void attachBibliography(Map<String, String> isbnByKey, ImportReport report) {
		// after step ② no two roots have the same ISBN: units sharing one are joined
		LongIntMap rootByIsbn = new LongIntMap();
		for (int unit = 0; unit < unitCount; unit++) {
			if (isbnOf[unit] != 0) {
				rootByIsbn.putIfAbsent(isbnOf[unit], unit);
			}
		}
		Groups groups = groups();
		for (int first = 0; first < unitCount; first++) {
			if (groups.count[first] == 0) {
				continue;
			}
			int root = find(first);
			if (isbnOf[root] != 0) {
				continue;
			}
			String key = keyOf(groups.members, groups.start[first], groups.end(first));
			String isbn = key == null ? null : isbnByKey.get(key);
			if (isbn == null) {
				continue;
			}
			report.nationalBibliographyIsbnAttached++;
			long code = encodeIsbn(isbn);
			int other = rootByIsbn.get(code);
			if (other >= 0) {
				union(other, root);
				isbnOf[find(root)] = code;
			}
			else {
				isbnOf[root] = code;
				rootByIsbn.putIfAbsent(code, root);
			}
		}
		report.booksAfterNationalBibliography = groupCount();
	}

	/** Step ④, as {@link BookGrouping#mergeSameText}; the keys are kept as 128-bit hashes. */
	@Override
	public void mergeSameText(ImportReport report) {
		Groups groups = groups();
		SameText keys = new SameText();
		for (int first = 0; first < unitCount; first++) {
			if (groups.count[first] == 0) {
				continue;
			}
			int from = groups.start[first];
			int to = groups.end(first);
			String key = textKey(groups.members, from, to);
			if (key == null) {
				continue;
			}
			LocalDate date = dateOf(groups.members, from, to);
			int before = keys.table.size();
			int id = keys.table.add(key);
			if (id < before) {
				report.sameTextMerged++;
				union(keys.firstUnit.get(id), first);
			}
			else {
				keys.firstUnit.set(id, first);
			}
			if (date != null) {
				keys.addDate(id, Math.toIntExact(date.toEpochDay()), key.hashCode());
			}
		}
		// merged books whose editions may differ: their groups' dates are 2+ years apart [확인 필요: 표본]
		int[] wide = new int[16];
		int wideCount = 0;
		for (int i = 0; i < keys.datedCount; i++) {
			int id = keys.datedOrder.get(i);
			if (keys.wide(id)) {
				if (wideCount == wide.length) {
					wide = Arrays.copyOf(wide, wideCount * 2);
				}
				wide[wideCount++] = i;
			}
		}
		report.sameTextWideDates += wideCount;
		int take = Math.min(wideCount, WIDE_DATE_SAMPLES - report.sameTextWideDateSamples.size());
		if (take > 0) {
			for (int i : wideDateSampleOrder(keys, Arrays.copyOf(wide, wideCount), take, groups)) {
				int id = keys.datedOrder.get(i);
				int from = groups.start[keys.firstUnit.get(id)];
				int to = groups.end(keys.firstUnit.get(id));
				report.sameTextWideDateSamples.add(LocalDate.ofEpochDay(keys.minDay.get(id)) + "~"
						+ LocalDate.ofEpochDay(keys.maxDay.get(id)) + " " + text(TITLE, titles, groups.members, from, to)
						+ " / " + text(AUTHOR, authors, groups.members, from, to) + " / "
						+ text(PUBLISHER, publishers, groups.members, from, to));
			}
		}
		report.booksAfterSameText = groupCount();
	}

	/**
	 * The first {@code take} wide keys (indexes into the dated order) in the order {@link BookGrouping} meets them:
	 * the iteration order of its {@link HashMap} filled by {@link HashMap#computeIfAbsent}, whose shape
	 * {@link MapShape} followed. When a bin whose order matters became a tree, the order is taken from a real
	 * {@link HashMap} filled again by the same calls.
	 */
	private int[] wideDateSampleOrder(SameText keys, int[] wide, int take, Groups groups) {
		MapShape shape = keys.shape;
		long[] sorted = new long[wide.length];
		for (int j = 0; j < wide.length; j++) {
			sorted[j] = shape.sortKey(wide[j]);
		}
		Arrays.sort(sorted);
		int[] order = new int[take];
		boolean ordered = !replaySampleOrder;
		for (int p = 0; p < take; p++) {
			order[p] = MapShape.index(sorted[p]);
			int bucket = (int) (sorted[p] >>> 32);
			boolean shared = p > 0 && (int) (sorted[p - 1] >>> 32) == bucket
					|| p + 1 < sorted.length && (int) (sorted[p + 1] >>> 32) == bucket;
			ordered &= !shared || shape.listBin(bucket);
		}
		if (ordered) {
			return order;
		}
		// the same calls as BookGrouping#mergeSameText: one per group with a key and a date
		Map<String, Integer> replay = new HashMap<>();
		for (int first = 0; first < unitCount; first++) {
			if (groups.count[first] == 0) {
				continue;
			}
			String key = textKey(groups.members, groups.start[first], groups.end(first));
			if (key != null && dateOf(groups.members, groups.start[first], groups.end(first)) != null) {
				replay.computeIfAbsent(key, k -> keys.dated.get(keys.table.find(k)) - 1);
			}
		}
		int at = 0;
		for (int i : replay.values()) {
			if (at < take && keys.wide(keys.datedOrder.get(i))) {
				order[at++] = i;
			}
		}
		return order;
	}

	/**
	 * The shape of a {@code new HashMap<>()} filled by {@link HashMap#computeIfAbsent} (JDK 25), followed call by call
	 * from the keys' {@link String#hashCode}: a call first doubles the table when it holds more than 3/4 (from 16
	 * buckets); a new key goes to the front of its bin; a new key finding 7 or more keys in its bin makes the bin a tree
	 * from 64 buckets on, and doubles the table below 64. Doubling keeps the order inside a bin. So, except in bins
	 * that became trees, the iteration order is by bucket, then newest key first.
	 */
	static final class MapShape {

		private final IntColumn hashes = new IntColumn();

		private int size;

		private int capacity;

		/** Keys per bin, up to 127. */
		private byte[] bins;

		/** Bins made trees: capacity then bucket. */
		private final List<long[]> trees = new ArrayList<>();

		/** One {@code computeIfAbsent} call; {@code added} when the key was not in the map. */
		void call(int hashCode, boolean added) {
			if (bins == null || size > capacity / 4 * 3) {
				resize();
			}
			if (!added) {
				return;
			}
			hashes.set(size, hashCode);
			int bin = bucket(size, capacity);
			int before = bins[bin];
			bins[bin] = (byte) Math.min(127, before + 1);
			size++;
			if (before >= 7) {
				if (capacity < 64) {
					resize();
				}
				else {
					trees.add(new long[] { capacity, bin });
				}
			}
		}

		/** Iteration order key of the key added {@code index}-th (from 0): bucket, then newest first. */
		long sortKey(int index) {
			return (long) bucket(index, capacity) << 32 | Integer.MAX_VALUE - index;
		}

		static int index(long sortKey) {
			return Integer.MAX_VALUE - (int) sortKey;
		}

		/** Whether the bin of a bucket (of the final table) never was a tree, so its order is newest first. */
		boolean listBin(int bucket) {
			for (long[] tree : trees) {
				if ((bucket & tree[0] - 1) == tree[1]) {
					return false;
				}
			}
			return true;
		}

		private int bucket(int index, int tableCapacity) {
			int h = hashes.get(index);
			return (h ^ h >>> 16) & tableCapacity - 1;
		}

		private void resize() {
			capacity = bins == null ? 16 : capacity * 2;
			bins = new byte[capacity];
			for (int i = 0; i < size; i++) {
				int bin = bucket(i, capacity);
				bins[bin] = (byte) Math.min(127, bins[bin] + 1);
			}
		}

	}

	/** Gives every book to {@code sink} in the order of its first row, then frees all but what {@link #bookOf} needs. */
	@Override
	public void forEachBook(ToLongFunction<Book> sink) {
		if (titles == null) {
			throw new IllegalStateException("The compact grouping gives its books once");
		}
		Groups groups = groups();
		bookOfUnit = new long[unitCount];
		for (int root = 0; root < unitCount; root++) {
			if (groups.count[root] == 0) {
				continue;
			}
			int from = groups.start[root];
			int to = groups.end(root);
			long id = sink.applyAsLong(book(root, groups.members, from, to));
			for (int i = from; i < to; i++) {
				bookOfUnit[groups.members[i]] = id;
			}
		}
		titles = null;
		authors = null;
		publishers = null;
		typeNames = null;
		typeIds = null;
		oddIsbns = null;
		oddIsbnIds = null;
		firstCounts = null;
		firstInts = null;
		firstType = null;
		firstIsbn = null;
		moreHead = null;
		moreNext = null;
		moreField = null;
		moreValue = null;
		moreCount = null;
		libraryFirst = null;
		libraryHead = null;
		libraryNodeId = null;
		libraryNodeNext = null;
		parent = null;
		isbnOf = null;
	}

	private Book book(int root, int[] members, int from, int to) {
		String title = text(TITLE, titles, members, from, to);
		String authorRaw = text(AUTHOR, authors, members, from, to);
		String publisher = text(PUBLISHER, publishers, members, from, to);
		long isbn = isbnOf[find(root)];
		long type = mostCommon(TYPE, members, from, to);
		return new Book(title, authorRaw, publisher, CatalogText.titleNorm(title), CatalogText.mainTitleNorm(title),
				CatalogText.publisherNorm(publisher), dateOf(members, from, to), isbn == 0 ? null : decodeIsbn(isbn),
				type < 0 ? null : typeNames.get((int) type), Contributors.parse(authorRaw),
				libraryIds(members, from, to));
	}

	private long[] libraryIds(int[] members, int from, int to) {
		long[] ids = new long[4];
		int size = 0;
		for (int i = from; i < to; i++) {
			int unit = members[i];
			if (size == ids.length) {
				ids = Arrays.copyOf(ids, size * 2);
			}
			ids[size++] = libraryFirst.get(unit);
			for (int node = libraryHead.get(unit); node != 0; node = libraryNodeNext.get(node - 1)) {
				if (size == ids.length) {
					ids = Arrays.copyOf(ids, size * 2);
				}
				ids[size++] = libraryNodeId.get(node - 1);
			}
		}
		Arrays.sort(ids, 0, size);
		int distinct = 0;
		for (int i = 0; i < size; i++) {
			if (distinct == 0 || ids[distinct - 1] != ids[i]) {
				ids[distinct++] = ids[i];
			}
		}
		return Arrays.copyOf(ids, distinct);
	}

	/** Same-text key (step ④): whole title, first author, publisher. */
	private String textKey(int[] members, int from, int to) {
		return BookGrouper.key(CatalogText.titleNorm(text(TITLE, titles, members, from, to)),
				BookGrouper.firstAuthor(Contributors.parse(text(AUTHOR, authors, members, from, to))),
				CatalogText.publisherNorm(text(PUBLISHER, publishers, members, from, to)));
	}

	/** National bibliography key (step ③): main title, first author, publisher. */
	private String keyOf(int[] members, int from, int to) {
		String title = text(TITLE, titles, members, from, to);
		String publisher = CatalogText.publisherNorm(text(PUBLISHER, publishers, members, from, to));
		String author = BookGrouper.firstAuthor(Contributors.parse(text(AUTHOR, authors, members, from, to)));
		return BookGrouper.key(CatalogText.mainTitleNorm(title), author, publisher);
	}

	private String text(int field, StringPool pool, int[] members, int from, int to) {
		long id = mostCommon(field, members, from, to);
		return id < 0 ? null : pool.get((int) id);
	}

	private LocalDate dateOf(int[] members, int from, int to) {
		count(DATE, members, from, to);
		if (tally.size == 0) {
			return null;
		}
		if (tally.size == 1) {
			return LocalDate.ofEpochDay(tally.values[0]);
		}
		Map<LocalDate, Long> counts = new HashMap<>();
		for (int i = 0; i < tally.size; i++) {
			counts.put(LocalDate.ofEpochDay(tally.values[i]), tally.counts[i]);
		}
		return PublishedDates.representative(counts);
	}

	/**
	 * Most common value of a field over the rows of the members [안]; on a tie the smallest by {@link String#compareTo}
	 * of the texts. -1 (ISBN: 0) when no row has one.
	 */
	private long mostCommon(int field, int[] members, int from, int to) {
		long none = field == ISBN ? 0 : -1;
		if (to - from == 1 && moreHead.get(members[from]) == 0) {
			int unit = members[from];
			return firstCounts[field].get(unit) > 0 ? firstValue(field, unit) : none;
		}
		count(field, members, from, to);
		if (tally.size == 0) {
			return none;
		}
		long best = tally.values[0];
		long bestCount = tally.counts[0];
		for (int i = 1; i < tally.size; i++) {
			long value = tally.values[i];
			long count = tally.counts[i];
			if (count > bestCount || count == bestCount && compare(field, value, best) < 0) {
				best = value;
				bestCount = count;
			}
		}
		return best;
	}

	private int compare(int field, long a, long b) {
		return switch (field) {
			case TITLE -> titles.compare((int) a, (int) b);
			case AUTHOR -> authors.compare((int) a, (int) b);
			case PUBLISHER -> publishers.compare((int) a, (int) b);
			case TYPE -> typeNames.get((int) a).compareTo(typeNames.get((int) b));
			case ISBN -> compareIsbn(a, b);
			default -> Long.compare(a, b);
		};
	}

	/** Fills {@link #tally} with the rows per value of a field over the members. */
	private void count(int field, int[] members, int from, int to) {
		tally.clear();
		for (int i = from; i < to; i++) {
			int unit = members[i];
			int count = firstCounts[field].get(unit);
			if (count > 0) {
				tally.add(firstValue(field, unit), count);
			}
			for (int node = moreHead.get(unit); node != 0; node = moreNext.get(node - 1)) {
				if (moreField.get(node - 1) == field) {
					tally.add(moreValue.get(node - 1), moreCount.get(node - 1));
				}
			}
		}
	}

	/** Units of every group by root: groups in the order of their first unit (= their root, = their first row). */
	private record Groups(int[] members, int[] start, int[] count) {

		int end(int root) {
			return start[root] + count[root];
		}

	}

	private Groups groups() {
		int n = unitCount;
		int[] count = new int[n];
		for (int i = 0; i < n; i++) {
			count[find(i)]++;
		}
		int[] start = new int[n];
		int at = 0;
		for (int i = 0; i < n; i++) {
			if (count[i] > 0) {
				start[i] = at;
				at += count[i];
			}
		}
		int[] members = new int[n];
		for (int i = 0; i < n; i++) {
			members[start[parent[i]]++] = i;
		}
		for (int i = 0; i < n; i++) {
			start[i] -= count[i];
		}
		return new Groups(members, start, count);
	}

	private long groupCount() {
		long count = 0;
		for (int i = 0; i < parent.length; i++) {
			if (find(i) == i) {
				count++;
			}
		}
		return count;
	}

	private void unionWithFirst(LongIntMap firstByKey, long key, int unit) {
		int first = firstByKey.putIfAbsent(key, unit);
		if (first >= 0) {
			union(first, unit);
		}
	}

	private void union(int a, int b) {
		int ra = find(a);
		int rb = find(b);
		if (ra == rb) {
			return;
		}
		int keep = Math.min(ra, rb);
		int drop = Math.max(ra, rb);
		parent[drop] = keep;
		long isbn = isbnOf[drop];
		isbnOf[drop] = 0;
		if (isbn != 0 && isbnOf[keep] == 0) {
			isbnOf[keep] = isbn;
		}
	}

	private int find(int i) {
		int root = i;
		while (parent[root] != root) {
			root = parent[root];
		}
		while (parent[i] != root) {
			int next = parent[i];
			parent[i] = root;
			i = next;
		}
		return root;
	}

	/** Step ④'s keys: per key its first group, its dates, and the order of the first dated group. */
	private static final class SameText {

		final KeyTable table = new KeyTable(1 << 10);

		final IntColumn firstUnit = new IntColumn();

		final IntColumn minDay = new IntColumn();

		final IntColumn maxDay = new IntColumn();

		/** Per key: its index in {@link #datedOrder} + 1; 0 when no group had a date. */
		final IntColumn dated = new IntColumn();

		final IntColumn datedOrder = new IntColumn();

		int datedCount;

		/** The shape of {@link BookGrouping}'s map of date ranges, for the order of the samples. */
		final MapShape shape = new MapShape();

		void addDate(int id, int day, int hashCode) {
			boolean added = dated.get(id) == 0;
			shape.call(hashCode, added);
			if (added) {
				datedOrder.set(datedCount, id);
				dated.set(id, ++datedCount);
				minDay.set(id, day);
				maxDay.set(id, day);
			}
			else {
				minDay.set(id, Math.min(minDay.get(id), day));
				maxDay.set(id, Math.max(maxDay.get(id), day));
			}
		}

		boolean wide(int id) {
			return LocalDate.ofEpochDay(minDay.get(id)).plusYears(2).isBefore(LocalDate.ofEpochDay(maxDay.get(id)));
		}

	}

	/**
	 * Keys kept as two 64-bit hashes (128 bits), numbered in the order first added; the text is not kept. Open
	 * addressing with linear probing over a power-of-two slot array that doubles past 3/4 full; a slot holds the key
	 * number + 1, and the hashes live in columns by key number.
	 */
	static final class KeyTable {

		private final LongColumn his = new LongColumn();

		private final LongColumn los = new LongColumn();

		private int size;

		private int[] slots;

		private int mask;

		private long hi;

		private long lo;

		KeyTable(int initialCapacity) {
			int capacity = Integer.highestOneBit(Math.max(2, initialCapacity - 1)) << 1;
			slots = new int[capacity];
			mask = capacity - 1;
		}

		int size() {
			return size;
		}

		/** The key's number, -1 when not added. */
		int find(String key) {
			hash(key);
			return find(hi, lo);
		}

		/** The key's number; a new key gets the next number. */
		int add(String key) {
			hash(key);
			return add(hi, lo);
		}

		int find(long h, long l) {
			for (int slot = slot(h);; slot = slot + 1 & mask) {
				int entry = slots[slot];
				if (entry == 0) {
					return -1;
				}
				if (his.get(entry - 1) == h && los.get(entry - 1) == l) {
					return entry - 1;
				}
			}
		}

		int add(long h, long l) {
			int slot = slot(h);
			for (;; slot = slot + 1 & mask) {
				int entry = slots[slot];
				if (entry == 0) {
					break;
				}
				if (his.get(entry - 1) == h && los.get(entry - 1) == l) {
					return entry - 1;
				}
			}
			int id = size++;
			his.set(id, h);
			los.set(id, l);
			slots[slot] = id + 1;
			if (size > slots.length / 4 * 3) {
				grow();
			}
			return id;
		}

		private int slot(long h) {
			return (int) h & mask;
		}

		private void grow() {
			slots = new int[slots.length * 2];
			mask = slots.length - 1;
			for (int id = 0; id < size; id++) {
				int slot = slot(his.get(id));
				while (slots[slot] != 0) {
					slot = slot + 1 & mask;
				}
				slots[slot] = id + 1;
			}
		}

		/**
		 * Two hashes of the UTF-16 chars, four chars per 64-bit block: a MurmurHash3-style round and an xxHash64-style
		 * round with other seeds and constants, each finished with the length and its own avalanche.
		 */
		private void hash(String s) {
			long a = 0x9E3779B97F4A7C15L;
			long b = 0x27D4EB2F165667C5L;
			int n = s.length();
			int i = 0;
			for (; i + 4 <= n; i += 4) {
				long block = s.charAt(i) | (long) s.charAt(i + 1) << 16 | (long) s.charAt(i + 2) << 32
						| (long) s.charAt(i + 3) << 48;
				a = roundA(a, block);
				b = roundB(b, block);
			}
			long block = 0;
			for (int shift = 0; i < n; i++, shift += 16) {
				block |= (long) s.charAt(i) << shift;
			}
			a = roundA(a, block);
			b = roundB(b, block);
			hi = avalancheA(a ^ n);
			lo = avalancheB(b ^ n);
		}

		private static long roundA(long h, long block) {
			long k = block * 0x87C37B91114253D5L;
			k = Long.rotateLeft(k, 31) * 0x4CF5AD432745937FL;
			h ^= k;
			return Long.rotateLeft(h, 27) * 5 + 0x52DCE729;
		}

		private static long roundB(long h, long block) {
			h += block * 0xC2B2AE3D27D4EB4FL;
			return Long.rotateLeft(h, 31) * 0x9E3779B185EBCA87L;
		}

		private static long avalancheA(long h) {
			h ^= h >>> 33;
			h *= 0xFF51AFD7ED558CCDL;
			h ^= h >>> 33;
			h *= 0xC4CEB9FE1A85EC53L;
			return h ^ h >>> 33;
		}

		private static long avalancheB(long h) {
			h ^= h >>> 33;
			h *= 0xC2B2AE3D27D4EB4FL;
			h ^= h >>> 29;
			h *= 0x165667B19E3779F9L;
			return h ^ h >>> 32;
		}

	}

	/**
	 * Distinct texts stored once, numbered in the order first seen. The chars live in pages of 1M chars (a text
	 * longer than a page gets a page of its own), each text after two chars of length; an id's address is the page
	 * number (11 bits) and the offset (20 bits).
	 */
	static final class StringPool {

		private static final int PAGE_BITS = 20;

		private static final int PAGE_CHARS = 1 << PAGE_BITS;

		private char[][] pages = new char[8][];

		private int pageCount;

		private int used;

		private final IntColumn addresses = new IntColumn();

		private int size;

		private int[] slots = new int[1 << 10];

		int idOf(String text) {
			int mask = slots.length - 1;
			for (int slot = spread(text.hashCode()) & mask;; slot = slot + 1 & mask) {
				int entry = slots[slot];
				if (entry == 0) {
					int id = store(text);
					slots[slot] = id + 1;
					if (size > slots.length / 4 * 3) {
						grow();
					}
					return id;
				}
				if (equals(entry - 1, text)) {
					return entry - 1;
				}
			}
		}

		String get(int id) {
			int address = addresses.get(id);
			char[] page = pages[address >>> PAGE_BITS];
			int at = address & PAGE_CHARS - 1;
			return new String(page, at + 2, length(page, at));
		}

		/** {@link String#compareTo} of two ids' texts. */
		int compare(int a, int b) {
			int addressA = addresses.get(a);
			int addressB = addresses.get(b);
			char[] pageA = pages[addressA >>> PAGE_BITS];
			char[] pageB = pages[addressB >>> PAGE_BITS];
			int atA = addressA & PAGE_CHARS - 1;
			int atB = addressB & PAGE_CHARS - 1;
			int lengthA = length(pageA, atA);
			int lengthB = length(pageB, atB);
			for (int i = 0, n = Math.min(lengthA, lengthB); i < n; i++) {
				char ca = pageA[atA + 2 + i];
				char cb = pageB[atB + 2 + i];
				if (ca != cb) {
					return ca - cb;
				}
			}
			return lengthA - lengthB;
		}

		private boolean equals(int id, String text) {
			int address = addresses.get(id);
			char[] page = pages[address >>> PAGE_BITS];
			int at = address & PAGE_CHARS - 1;
			int length = length(page, at);
			if (length != text.length()) {
				return false;
			}
			for (int i = 0; i < length; i++) {
				if (page[at + 2 + i] != text.charAt(i)) {
					return false;
				}
			}
			return true;
		}

		private static int length(char[] page, int at) {
			return page[at] << 16 | page[at + 1];
		}

		private int store(String text) {
			int need = text.length() + 2;
			if (pageCount == 0 || used + need > pages[pageCount - 1].length) {
				if (pageCount == 1 << 31 - PAGE_BITS) {
					throw new IllegalStateException("String pool full (2^31 chars)");
				}
				if (pageCount == pages.length) {
					pages = Arrays.copyOf(pages, pageCount * 2);
				}
				pages[pageCount++] = new char[Math.max(PAGE_CHARS, need)];
				used = 0;
			}
			char[] page = pages[pageCount - 1];
			page[used] = (char) (text.length() >>> 16);
			page[used + 1] = (char) text.length();
			text.getChars(0, text.length(), page, used + 2);
			int id = size++;
			addresses.set(id, (pageCount - 1) << PAGE_BITS | used);
			used += need;
			return id;
		}

		private void grow() {
			slots = new int[slots.length * 2];
			int mask = slots.length - 1;
			for (int id = 0; id < size; id++) {
				int address = addresses.get(id);
				char[] page = pages[address >>> PAGE_BITS];
				int at = address & PAGE_CHARS - 1;
				int h = 0;
				for (int i = 0, n = length(page, at); i < n; i++) {
					h = 31 * h + page[at + 2 + i];
				}
				int slot = spread(h) & mask;
				while (slots[slot] != 0) {
					slot = slot + 1 & mask;
				}
				slots[slot] = id + 1;
			}
		}

		private static int spread(int h) {
			h *= 0x9E3779B9;
			return h ^ h >>> 16;
		}

	}

	/** Rows per value of one group, reused for every group. */
	private static final class Tally {

		long[] values = new long[16];

		long[] counts = new long[16];

		int size;

		/** Index + 1 of each value, by open addressing. */
		private int[] slots = new int[32];

		void clear() {
			int mask = slots.length - 1;
			for (int i = 0; i < size; i++) {
				int slot = slotOf(values[i], mask);
				while (slots[slot] != i + 1) {
					slot = slot + 1 & mask;
				}
				slots[slot] = 0;
			}
			size = 0;
		}

		void add(long value, long count) {
			int mask = slots.length - 1;
			int slot = slotOf(value, mask);
			for (;; slot = slot + 1 & mask) {
				int entry = slots[slot];
				if (entry == 0) {
					break;
				}
				if (values[entry - 1] == value) {
					counts[entry - 1] += count;
					return;
				}
			}
			if (size == values.length) {
				values = Arrays.copyOf(values, size * 2);
				counts = Arrays.copyOf(counts, size * 2);
			}
			values[size] = value;
			counts[size] = count;
			slots[slot] = ++size;
			if (size * 2 > slots.length) {
				slots = new int[slots.length * 2];
				int newMask = slots.length - 1;
				for (int i = 0; i < size; i++) {
					int s = slotOf(values[i], newMask);
					while (slots[s] != 0) {
						s = s + 1 & newMask;
					}
					slots[s] = i + 1;
				}
			}
		}

		private static int slotOf(long value, int mask) {
			long h = value * 0x9E3779B97F4A7C15L;
			return (int) (h ^ h >>> 32) & mask;
		}

	}

	/** Long keys (never 0) to int values (never negative), open addressing. */
	private static final class LongIntMap {

		private long[] keys = new long[1 << 10];

		private int[] values = new int[1 << 10];

		private int size;

		/** The value already kept for the key, or -1 after keeping {@code value}. */
		int putIfAbsent(long key, int value) {
			int mask = keys.length - 1;
			int slot = slotOf(key, mask);
			for (; keys[slot] != 0; slot = slot + 1 & mask) {
				if (keys[slot] == key) {
					return values[slot];
				}
			}
			keys[slot] = key;
			values[slot] = value;
			if (++size > keys.length / 4 * 3) {
				grow();
			}
			return -1;
		}

		int get(long key) {
			int mask = keys.length - 1;
			for (int slot = slotOf(key, mask); keys[slot] != 0; slot = slot + 1 & mask) {
				if (keys[slot] == key) {
					return values[slot];
				}
			}
			return -1;
		}

		private void grow() {
			long[] oldKeys = keys;
			int[] oldValues = values;
			keys = new long[oldKeys.length * 2];
			values = new int[oldKeys.length * 2];
			int mask = keys.length - 1;
			for (int i = 0; i < oldKeys.length; i++) {
				if (oldKeys[i] != 0) {
					int slot = slotOf(oldKeys[i], mask);
					while (keys[slot] != 0) {
						slot = slot + 1 & mask;
					}
					keys[slot] = oldKeys[i];
					values[slot] = oldValues[i];
				}
			}
		}

		private static int slotOf(long key, int mask) {
			long h = key * 0x9E3779B97F4A7C15L;
			return (int) (h ^ h >>> 32) & mask;
		}

	}

	/** Ints by index in pages of 16K; unwritten entries read 0. */
	static final class IntColumn {

		private int[][] pages = new int[16][];

		int get(int i) {
			int p = i >>> PAGE_BITS;
			int[] page = p < pages.length ? pages[p] : null;
			return page == null ? 0 : page[i & PAGE_MASK];
		}

		void set(int i, int value) {
			int p = i >>> PAGE_BITS;
			if (p >= pages.length) {
				pages = Arrays.copyOf(pages, Math.max(p + 1, pages.length * 2));
			}
			if (pages[p] == null) {
				pages[p] = new int[PAGE];
			}
			pages[p][i & PAGE_MASK] = value;
		}

	}

	/** Longs by index in pages of 16K; unwritten entries read 0. */
	static final class LongColumn {

		private long[][] pages = new long[16][];

		long get(int i) {
			int p = i >>> PAGE_BITS;
			long[] page = p < pages.length ? pages[p] : null;
			return page == null ? 0 : page[i & PAGE_MASK];
		}

		void set(int i, long value) {
			int p = i >>> PAGE_BITS;
			if (p >= pages.length) {
				pages = Arrays.copyOf(pages, Math.max(p + 1, pages.length * 2));
			}
			if (pages[p] == null) {
				pages[p] = new long[PAGE];
			}
			pages[p][i & PAGE_MASK] = value;
		}

	}

	/** Bytes by index in pages of 16K; unwritten entries read 0. */
	static final class ByteColumn {

		private byte[][] pages = new byte[16][];

		byte get(int i) {
			int p = i >>> PAGE_BITS;
			byte[] page = p < pages.length ? pages[p] : null;
			return page == null ? 0 : page[i & PAGE_MASK];
		}

		void set(int i, byte value) {
			int p = i >>> PAGE_BITS;
			if (p >= pages.length) {
				pages = Arrays.copyOf(pages, Math.max(p + 1, pages.length * 2));
			}
			if (pages[p] == null) {
				pages[p] = new byte[PAGE];
			}
			pages[p][i & PAGE_MASK] = value;
		}

	}

}
