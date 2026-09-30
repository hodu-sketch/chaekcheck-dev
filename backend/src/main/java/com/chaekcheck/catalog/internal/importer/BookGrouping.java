package com.chaekcheck.catalog.internal.importer;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.chaekcheck.catalog.internal.normalize.CatalogText;
import com.chaekcheck.catalog.internal.normalize.Contributors;
import com.chaekcheck.catalog.internal.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.internal.normalize.Contributors.Role;
import com.chaekcheck.catalog.internal.normalize.Isbn;
import com.chaekcheck.catalog.internal.normalize.PublishedDates;

/**
 * Groups collected rows into books (research same-book-isbn 5절, handoff 5단계 "묶기" ①~③, ④ by user decision
 * 2026-09-30). A later step only adds rows that an earlier step left apart.
 * <ol>
 * <li>Same platform code. Kyobo, Kyobo subscription and YES24 codes share one numbering (2026-09-30 data check:
 * 98~99.5% of shared codes have the same title across these screen kinds). Portal {@code book_idx} and Gangnam
 * {@code book_num} are per site, so they group inside one library only.</li>
 * <li>Paper ISBN computed from a Kyobo-style code ({@link Isbn#fromKyoboCode}), from the item key or the alt code.</li>
 * <li>National bibliography ISBN, when (main title, first author, publisher) matches exactly one record.</li>
 * <li>Same whole title, first author and publisher ({@link #mergeSameText}).</li>
 * </ol>
 */
final class BookGrouping {

	/**
	 * Screen kinds whose item keys are one shared numbering. Sites may change their numbering, so this is checked
	 * again with every library list check (docs/library-maintenance.md 4-11, scripts/library/check_code_spaces.py).
	 */
	private static final Set<String> SHARED_CODE_KINDS = Set.of("KYOBO_V1", "KYOBO_SUBSCRIPTION_V1", "YES24_V1",
			"YES24_V2", "YES24_V3");

	private static final char SEP = '\u0001';

	/** One book to write. {@code rows} are indexes into the row list. */
	record Book(String title, String authorDisplay, String publisher, String titleNorm, String mainTitleNorm,
			String publisherNorm, LocalDate publishedOn, String isbn13, String contentType,
			List<Contributor> contributors, int[] rows) {
	}

	private final List<CollectedRow> rows;

	private final int[] parent;

	/** ISBN found for a row's group; kept on the group root. */
	private final Map<Integer, String> isbnOfRoot = new HashMap<>();

	private BookGrouping(List<CollectedRow> rows) {
		this.rows = rows;
		this.parent = new int[rows.size()];
		for (int i = 0; i < parent.length; i++) {
			parent[i] = i;
		}
	}

	/** Steps ① and ②. */
	static BookGrouping byCodes(List<CollectedRow> rows, ImportReport report) {
		BookGrouping grouping = new BookGrouping(rows);
		Map<String, Integer> byCode = new HashMap<>();
		for (int i = 0; i < rows.size(); i++) {
			CollectedRow row = rows.get(i);
			String space = SHARED_CODE_KINDS.contains(row.platformType()) ? "SHARED" : "LIB" + row.libraryId();
			grouping.unionWithFirst(byCode, space + SEP + row.itemKey(), i);
		}
		report.booksAfterPlatformCode = grouping.groupCount();

		Map<String, Integer> byIsbn = new HashMap<>();
		for (int i = 0; i < rows.size(); i++) {
			String isbn = kyoboIsbn(rows.get(i), report);
			if (isbn != null) {
				grouping.unionWithFirst(byIsbn, isbn, i);
			}
		}
		// the ISBN of a group: the one most of its rows computed
		Map<Integer, Map<String, Integer>> counts = new HashMap<>();
		for (int i = 0; i < rows.size(); i++) {
			String isbn = kyoboIsbn(rows.get(i), null);
			if (isbn != null) {
				counts.computeIfAbsent(grouping.find(i), r -> new HashMap<>()).merge(isbn, 1, Integer::sum);
			}
		}
		counts.forEach((root, byValue) -> grouping.isbnOfRoot.put(root, mostCommon(byValue)));
		report.booksAfterKyoboIsbn = grouping.groupCount();
		return grouping;
	}

	/**
	 * Keys (main title, first author, publisher) of groups without an ISBN, for the national bibliography lookup.
	 * Groups without an author or publisher have no key (not merged when unsure).
	 */
	Set<String> bibliographyKeys() {
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
	void attachBibliography(Map<String, String> isbnByKey, ImportReport report) {
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
	void mergeSameText(ImportReport report) {
		Map<String, Integer> firstByKey = new HashMap<>();
		Map<String, LocalDate[]> dateRange = new HashMap<>();
		Map<String, String> sampleOf = new HashMap<>();
		for (int[] members : groups()) {
			String title = mostCommonOf(members, CollectedRow::title);
			String publisher = CatalogText.publisherNorm(mostCommonOf(members, CollectedRow::publisher));
			String author = firstAuthor(Contributors.parse(mostCommonOf(members, CollectedRow::authorRaw)));
			String key = key(CatalogText.titleNorm(title), author, publisher);
			if (key == null) {
				continue;
			}
			List<LocalDate> dates = new ArrayList<>(members.length);
			for (int i : members) {
				dates.add(rows.get(i).publishedOn());
			}
			LocalDate date = PublishedDates.representative(dates);
			if (firstByKey.containsKey(key)) {
				report.sameTextMerged++;
			}
			unionWithFirst(firstByKey, key, members[0]);
			if (date != null) {
				LocalDate[] range = dateRange.computeIfAbsent(key, k -> new LocalDate[] { date, date });
				range[0] = date.isBefore(range[0]) ? date : range[0];
				range[1] = date.isAfter(range[1]) ? date : range[1];
			}
			sampleOf.putIfAbsent(key, title + " / " + mostCommonOf(members, CollectedRow::authorRaw) + " / "
					+ mostCommonOf(members, CollectedRow::publisher));
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

	List<Book> books() {
		List<Book> books = new ArrayList<>();
		for (int[] members : groups()) {
			books.add(book(members));
		}
		return books;
	}

	private Book book(int[] members) {
		String title = mostCommonOf(members, CollectedRow::title);
		String authorRaw = mostCommonOf(members, CollectedRow::authorRaw);
		String publisher = mostCommonOf(members, CollectedRow::publisher);
		List<LocalDate> dates = new ArrayList<>(members.length);
		for (int i : members) {
			dates.add(rows.get(i).publishedOn());
		}
		String isbn = isbnOfRoot.get(find(members[0]));
		return new Book(title, authorRaw, publisher, CatalogText.titleNorm(title), CatalogText.mainTitleNorm(title),
				CatalogText.publisherNorm(publisher), PublishedDates.representative(dates), isbn,
				mostCommonOf(members, CollectedRow::contentType), Contributors.parse(authorRaw), members);
	}

	private String keyOf(int[] members) {
		String title = mostCommonOf(members, CollectedRow::title);
		String publisher = CatalogText.publisherNorm(mostCommonOf(members, CollectedRow::publisher));
		String author = firstAuthor(Contributors.parse(mostCommonOf(members, CollectedRow::authorRaw)));
		return key(CatalogText.mainTitleNorm(title), author, publisher);
	}

	/** The comparison key; null when a part is missing. Shared with {@link NationalBibliography}. */
	static String key(String mainTitleNorm, String authorExact, String publisherNorm) {
		if (mainTitleNorm == null || mainTitleNorm.isEmpty() || authorExact == null || authorExact.isEmpty()
				|| publisherNorm == null || publisherNorm.isEmpty()) {
			return null;
		}
		return mainTitleNorm + SEP + authorExact + SEP + publisherNorm;
	}

	/** Exact name of the first author (AUTHOR or UNKNOWN role); null when there is none. */
	static String firstAuthor(List<Contributor> contributors) {
		return contributors.stream()
			.filter(c -> c.role() == Role.AUTHOR || c.role() == Role.UNKNOWN)
			.map(Contributor::nameExact)
			.filter(name -> !name.isEmpty())
			.findFirst()
			.orElse(null);
	}

	private static String kyoboIsbn(CollectedRow row, ImportReport report) {
		Isbn.Kyobo fromKey = Isbn.fromKyoboCode(row.itemKey());
		Isbn.Kyobo result = fromKey.result() == Isbn.KyoboResult.NOT_KYOBO_CODE ? Isbn.fromKyoboCode(row.altCode())
				: fromKey;
		if (report != null) {
			report.kyoboCodes.merge(result.result().name(), 1L, Long::sum);
		}
		return result.isbn13();
	}

	private String mostCommonOf(int[] members, Function<CollectedRow, String> field) {
		Map<String, Integer> counts = new HashMap<>();
		for (int i : members) {
			String value = field.apply(rows.get(i));
			if (value != null && !value.isBlank()) {
				counts.merge(value, 1, Integer::sum);
			}
		}
		return counts.isEmpty() ? null : mostCommon(counts);
	}

	/** Most common value [안]; on a tie the smallest string, so the result does not depend on row order. */
	private static String mostCommon(Map<String, Integer> counts) {
		return counts.entrySet()
			.stream()
			.min(Comparator.<Map.Entry<String, Integer>>comparingInt(e -> -e.getValue())
				.thenComparing(Map.Entry::getKey))
			.orElseThrow()
			.getKey();
	}

	/** Row indexes of every group, groups in the order of their first row. */
	private List<int[]> groups() {
		int n = rows.size();
		int[] roots = new int[n];
		Map<Integer, Integer> sizes = new HashMap<>();
		for (int i = 0; i < n; i++) {
			roots[i] = find(i);
			sizes.merge(roots[i], 1, Integer::sum);
		}
		Map<Integer, int[]> members = new HashMap<>();
		Map<Integer, Integer> filled = new HashMap<>();
		List<int[]> ordered = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			int root = roots[i];
			int[] group = members.get(root);
			if (group == null) {
				group = new int[sizes.get(root)];
				members.put(root, group);
				ordered.add(group);
			}
			int at = filled.merge(root, 1, Integer::sum) - 1;
			group[at] = i;
		}
		return ordered;
	}

	private long groupCount() {
		return Arrays.stream(parentsRoots()).distinct().count();
	}

	private int[] parentsRoots() {
		int[] roots = new int[parent.length];
		for (int i = 0; i < parent.length; i++) {
			roots[i] = find(i);
		}
		return roots;
	}

	private void unionWithFirst(Map<String, Integer> firstByKey, String key, int row) {
		Integer first = firstByKey.putIfAbsent(key, row);
		if (first != null) {
			union(first, row);
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
