package com.chaekcheck.collection.internal.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.catalog.normalize.PublishedDates;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * Groups collected rows into books (research same-book-isbn 5절, ④ by user decision 2026-09-30). A later step only
 * adds rows that an earlier step left apart.
 * <ol>
 * <li>Same platform code. Kyobo, Kyobo subscription and YES24 codes share one numbering (2026-09-30 data check:
 * 98~99.5% of shared codes have the same title across these screen kinds). Other screens' codes (portal
 * {@code book_idx}, Gangnam {@code book_num}, Bookcube, Aladin) group inside one library only.</li>
 * <li>Paper ISBN computed from a Kyobo-style code ({@link Isbn#fromKyoboCode}), from the item key or the alt code.</li>
 * <li>National bibliography ISBN, when (main title, first author, publisher) matches exactly one record.</li>
 * <li>Same whole title, first author and publisher ({@link #mergeSameText}).</li>
 * </ol>
 * To import every service library without keeping every row in memory (ADR-0054), the first pass keeps one
 * {@link Unit} per step-① group with the number of rows per value instead of the rows: the most common value of a
 * book is then the same as counted over its rows. The second pass finds a row's book through its unit.
 * <p>
 * The grouping {@code objects} (cycle 11, ADR-0079; {@code current} until 2026-10-12, renamed by the user): one
 * object per unit, the reference the other two are tested against. The default
 * grouping is {@code compact} since 2026-10-12.
 */
final class BookGrouping implements BookGrouper {

	/** Rows with the same code (step ①), as counts per value. */
	private static final class Unit {

		final Votes<String> titles = new Votes<>();

		final Votes<String> authors = new Votes<>();

		final Votes<String> publishers = new Votes<>();

		final Votes<String> contentTypes = new Votes<>();

		final Votes<String> isbns = new Votes<>();

		final Votes<LocalDate> dates = new Votes<>();

		/** One row per library: (library, item key) is unique in the collection DB. */
		long[] libraries = new long[1];

		int libraryCount;

		void addLibrary(long id) {
			if (libraryCount == libraries.length) {
				libraries = Arrays.copyOf(libraries, libraryCount * 2);
			}
			libraries[libraryCount++] = id;
		}

	}

	/**
	 * How many rows have each value; nulls and blank strings are not counted. Most units have one value, kept
	 * without a map.
	 */
	static final class Votes<T> {

		private T first;

		private long firstCount;

		private Map<T, Long> more;

		void add(T value) {
			if (value == null || value instanceof String text && text.isBlank()) {
				return;
			}
			if (first == null) {
				first = value;
				firstCount = 1;
			}
			else if (first.equals(value)) {
				firstCount++;
			}
			else {
				if (more == null) {
					more = new HashMap<>(4);
				}
				more.merge(value, 1L, Long::sum);
			}
		}

		void forEach(BiConsumer<T, Long> action) {
			if (first != null) {
				action.accept(first, firstCount);
			}
			if (more != null) {
				more.forEach(action);
			}
		}

	}

	private final Map<String, Integer> unitByKey = new HashMap<>();

	private final List<Unit> units = new ArrayList<>();

	private int[] parent;

	/** ISBN found for a group; kept on the group root. */
	private final Map<Integer, String> isbnOfRoot = new HashMap<>();

	/** The id the book of each step-① group got, set by {@link #forEachBook}. */
	private long[] bookOfUnit;

	/** First pass: counts one row in its step-① group. */
	@Override
	public void add(Row row, ImportReport report) {
		int index = unitByKey.computeIfAbsent(BookGrouper.unitKey(row), key -> {
			units.add(new Unit());
			return units.size() - 1;
		});
		Unit unit = units.get(index);
		unit.titles.add(row.title());
		unit.authors.add(row.authorRaw());
		unit.publishers.add(row.publisher());
		unit.contentTypes.add(row.contentType());
		unit.dates.add(row.publishedOn());
		unit.isbns.add(BookGrouper.kyoboIsbn(row, report));
		unit.addLibrary(row.library().serviceId());
	}

	/** Second pass: the step-① group of a row the first pass counted. */
	private int unitOf(Row row) {
		Integer index = unitByKey.get(BookGrouper.unitKey(row));
		if (index == null) {
			throw new IllegalStateException("Row not seen in the first pass: " + BookGrouper.unitKey(row));
		}
		return index;
	}

	@Override
	public long bookOf(Row row) {
		return bookOfUnit[unitOf(row)];
	}

	/** Steps ① (already the units) and ②. */
	@Override
	public void groupByCodes(ImportReport report) {
		parent = new int[units.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
		report.booksAfterPlatformCode = units.size();

		Map<String, Integer> byIsbn = new HashMap<>();
		for (int i = 0; i < units.size(); i++) {
			int unit = i;
			units.get(i).isbns.forEach((isbn, rows) -> unionWithFirst(byIsbn, isbn, unit));
		}
		// the ISBN of a group: the one most of its rows computed
		for (int[] members : groups()) {
			String isbn = mostCommonOf(members, unit -> unit.isbns);
			if (isbn != null) {
				isbnOfRoot.put(find(members[0]), isbn);
			}
		}
		report.booksAfterKyoboIsbn = groupCount();
	}

	/**
	 * Keys (main title, first author, publisher) of groups without an ISBN, for the national bibliography lookup.
	 * Groups without an author or publisher have no key (not merged when unsure).
	 */
	@Override
	public Set<String> bibliographyKeys() {
		Map<String, Boolean> keys = new HashMap<>();
		for (int[] members : groups()) {
			int root = find(members[0]);
			if (!isbnOfRoot.containsKey(root)) {
				String key = keyOf(members);
				if (key != null) {
					keys.put(key, Boolean.TRUE);
				}
			}
		}
		return keys.keySet();
	}

	/**
	 * Step ③: a group without an ISBN whose key has one national bibliography ISBN gets it, and groups with the same
	 * ISBN (also one found in step ②) are merged.
	 */
	@Override
	public void attachBibliography(Map<String, String> isbnByKey, ImportReport report) {
		Map<String, Integer> rootByIsbn = new HashMap<>();
		isbnOfRoot.forEach((root, isbn) -> rootByIsbn.putIfAbsent(isbn, root));
		for (int[] members : groups()) {
			int root = find(members[0]);
			if (isbnOfRoot.containsKey(root)) {
				continue;
			}
			String key = keyOf(members);
			String isbn = key == null ? null : isbnByKey.get(key);
			if (isbn == null) {
				continue;
			}
			report.nationalBibliographyIsbnAttached++;
			Integer other = rootByIsbn.get(isbn);
			if (other != null) {
				union(other, root);
				isbnOfRoot.put(find(root), isbn);
			}
			else {
				isbnOfRoot.put(root, isbn);
				rootByIsbn.put(isbn, root);
			}
		}
		report.booksAfterNationalBibliography = groupCount();
	}

	/**
	 * Step ④ (user decision 2026-09-30): groups whose whole title, first author and publisher are the same after
	 * normalizing become one book, even when their codes or ISBNs differ. The whole title keeps edition marks such as
	 * "(개정판)" or "(40주년 기념판)", so those stay separate books; an edition not marked in the title is merged.
	 * Groups without an author or publisher are never merged (not merged when unsure). When merged groups had
	 * different ISBNs, the book keeps one of them.
	 */
	@Override
	public void mergeSameText(ImportReport report) {
		Map<String, Integer> firstByKey = new HashMap<>();
		Map<String, LocalDate[]> dateRange = new HashMap<>();
		Map<String, String> sampleOf = new HashMap<>();
		for (int[] members : groups()) {
			String title = mostCommonOf(members, unit -> unit.titles);
			String authorRaw = mostCommonOf(members, unit -> unit.authors);
			String publisherRaw = mostCommonOf(members, unit -> unit.publishers);
			String key = BookGrouper.key(CatalogText.titleNorm(title),
					BookGrouper.firstAuthor(Contributors.parse(authorRaw)), CatalogText.publisherNorm(publisherRaw));
			if (key == null) {
				continue;
			}
			LocalDate date = dateOf(members);
			if (firstByKey.containsKey(key)) {
				report.sameTextMerged++;
			}
			unionWithFirst(firstByKey, key, members[0]);
			if (date != null) {
				LocalDate[] range = dateRange.computeIfAbsent(key, k -> new LocalDate[] { date, date });
				range[0] = date.isBefore(range[0]) ? date : range[0];
				range[1] = date.isAfter(range[1]) ? date : range[1];
			}
			sampleOf.putIfAbsent(key, title + " / " + authorRaw + " / " + publisherRaw);
		}
		// merged books whose editions may differ: their groups' dates are 2+ years apart [확인 필요: 표본]
		dateRange.forEach((key, range) -> {
			if (range[0].plusYears(2).isBefore(range[1])) {
				report.sameTextWideDates++;
				if (report.sameTextWideDateSamples.size() < 40) {
					report.sameTextWideDateSamples.add(range[0] + "~" + range[1] + " " + sampleOf.get(key));
				}
			}
		});
		report.booksAfterSameText = groupCount();
	}

	/** Gives every book to {@code sink} in the order of its first row and keeps, per step-① group, the id. */
	@Override
	public void forEachBook(ToLongFunction<Book> sink) {
		bookOfUnit = new long[units.size()];
		for (int[] members : groups()) {
			long id = sink.applyAsLong(book(members));
			for (int unit : members) {
				bookOfUnit[unit] = id;
			}
		}
	}

	private Book book(int[] members) {
		String title = mostCommonOf(members, unit -> unit.titles);
		String authorRaw = mostCommonOf(members, unit -> unit.authors);
		String publisher = mostCommonOf(members, unit -> unit.publishers);
		String isbn = isbnOfRoot.get(find(members[0]));
		return new Book(title, authorRaw, publisher, CatalogText.titleNorm(title), CatalogText.mainTitleNorm(title),
				CatalogText.publisherNorm(publisher), dateOf(members), isbn,
				mostCommonOf(members, unit -> unit.contentTypes), Contributors.parse(authorRaw), libraryIds(members));
	}

	private LocalDate dateOf(int[] members) {
		Map<LocalDate, Long> counts = new HashMap<>();
		for (int i : members) {
			units.get(i).dates.forEach((date, rows) -> counts.merge(date, rows, Long::sum));
		}
		return PublishedDates.representative(counts);
	}

	private long[] libraryIds(int[] members) {
		int size = 0;
		for (int i : members) {
			size += units.get(i).libraryCount;
		}
		long[] ids = new long[size];
		int at = 0;
		for (int i : members) {
			Unit unit = units.get(i);
			System.arraycopy(unit.libraries, 0, ids, at, unit.libraryCount);
			at += unit.libraryCount;
		}
		return Arrays.stream(ids).sorted().distinct().toArray();
	}

	private String keyOf(int[] members) {
		String title = mostCommonOf(members, unit -> unit.titles);
		String publisher = CatalogText.publisherNorm(mostCommonOf(members, unit -> unit.publishers));
		String author = BookGrouper.firstAuthor(Contributors.parse(mostCommonOf(members, unit -> unit.authors)));
		return BookGrouper.key(CatalogText.mainTitleNorm(title), author, publisher);
	}

	/** Most common value over the rows of the members [안]; on a tie the smallest, so row order does not matter. */
	private String mostCommonOf(int[] members, Function<Unit, Votes<String>> field) {
		Map<String, Long> counts = new HashMap<>();
		for (int i : members) {
			field.apply(units.get(i)).forEach((value, rows) -> counts.merge(value, rows, Long::sum));
		}
		return counts.entrySet()
			.stream()
			.min(Comparator.<Map.Entry<String, Long>>comparingLong(e -> -e.getValue()).thenComparing(Map.Entry::getKey))
			.map(Map.Entry::getKey)
			.orElse(null);
	}

	/** Unit indexes of every group, groups in the order of their first unit (= their first row). */
	private List<int[]> groups() {
		int n = units.size();
		int[] roots = new int[n];
		int[] sizes = new int[n];
		for (int i = 0; i < n; i++) {
			roots[i] = find(i);
			sizes[roots[i]]++;
		}
		int[][] members = new int[n][];
		int[] filled = new int[n];
		List<int[]> ordered = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int root = roots[i];
			if (members[root] == null) {
				members[root] = new int[sizes[root]];
				ordered.add(members[root]);
			}
			members[root][filled[root]++] = i;
		}
		return ordered;
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

	private void unionWithFirst(Map<String, Integer> firstByKey, String key, int unit) {
		Integer first = firstByKey.putIfAbsent(key, unit);
		if (first != null) {
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
		String isbn = isbnOfRoot.remove(drop);
		if (isbn != null) {
			isbnOfRoot.putIfAbsent(keep, isbn);
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

}
