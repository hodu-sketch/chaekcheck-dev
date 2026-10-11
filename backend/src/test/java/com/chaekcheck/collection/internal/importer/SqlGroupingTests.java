package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.chaekcheck.catalog.normalize.Isbn;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Library;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * The grouping {@code sql} ({@link SqlGrouping}, cycle 11 step 10) against db-test: the shared contract, then the
 * same books, rows, counts and samples as {@link BookGrouping} for random rows, a long chain of ISBN links, a tie
 * between characters whose UTF-16 and code point orders differ, and the pass limit. Only temporary tables are used,
 * so nothing is left behind and other tests are not touched.
 */
@SpringBootTest
class SqlGroupingTests {

	@Autowired
	DataSource dataSource;

	@Test
	void keepsTheContract() {
		BookGrouperContractTests.assertContract(() -> new SqlGrouping(dataSource));
	}

	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = { 1, 2, 3, 4, 5, 6, 7, 8 })
	void givesWhatCurrentGives(long seed) {
		List<Row> rows = randomRows(new Random(seed), 1500);
		Result current = run(BookGrouping::new, rows, seed);
		long started = System.nanoTime();
		Result sql = run(() -> new SqlGrouping(dataSource), rows, seed);
		System.out.printf("seed %d: %d rows, %d books, sql %d ms, passes %s, wide %d%n", seed, rows.size(),
				sql.books.size(), (System.nanoTime() - started) / 1_000_000, sql.passes, sql.report.sameTextWideDates);
		assertSame(sql, current);
		// the random rows reach every step
		assertThat(current.report.booksAfterKyoboIsbn).isLessThan(current.report.booksAfterPlatformCode);
		assertThat(current.report.nationalBibliographyIsbnAttached).isPositive();
		assertThat(current.report.booksAfterNationalBibliography).isLessThan(current.report.booksAfterKyoboIsbn);
		assertThat(current.report.sameTextMerged).isPositive();
	}

	@Test
	void followsALongChain() {
		// unit i has the ISBNs i and i + 1 (its Kyobo row and its YES24 row), in reverse reading order
		int units = 3000;
		List<Row> rows = new ArrayList<>();
		for (int i = units - 1; i >= 0; i--) {
			rows.add(row(KYOBO, "K" + i, isbnCode(i), "책 " + i, "가", "나", null));
			rows.add(row(YES24, "K" + i, isbnCode(i + 1), "책 " + i, "가", "나", null));
		}
		Result current = run(BookGrouping::new, rows, 0);
		Result sql = run(() -> new SqlGrouping(dataSource), rows, 0);
		System.out.printf("chain of %d units: passes %s%n", units, sql.passes);
		assertSame(sql, current);
		assertThat(sql.books).hasSize(1);
		assertThat(sql.passes.getFirst()).isGreaterThan(5);
	}

	@Test
	void stopsAtThePassLimit() {
		List<Row> rows = new ArrayList<>();
		for (int i = 99; i >= 0; i--) {
			rows.add(row(KYOBO, "K" + i, isbnCode(i), "책", "가", "나", null));
			rows.add(row(YES24, "K" + i, isbnCode(i + 1), "책", "가", "나", null));
		}
		ImportReport report = new ImportReport();
		try (SqlGrouping grouping = new SqlGrouping(dataSource, 2)) {
			rows.forEach(row -> grouping.add(row, report));
			assertThatThrownBy(() -> grouping.groupByCodes(report)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("did not settle in 2 passes");
		}
	}

	/**
	 * U+1F600 is {@code D83D DE00} in UTF-16 and U+FF21 is {@code FF21}: Java orders the emoji first, PostgreSQL's C
	 * collation (code points) the other way. A tie between them goes to the emoji, as in {@link BookGrouping}.
	 */
	@Test
	void breaksTiesInJavaOrder() {
		String emoji = "😀 책";
		String fullWidth = "Ａ 책";
		List<Row> rows = List.of(row(KYOBO, "K1", null, fullWidth, "Ａ", "😀", null),
				row(YES24, "K1", null, emoji, "😀", "Ａ", null),
				row(PORTAL_A, "P1", null, "", "가", "나", null),
				row(PORTAL_A, "P1", null, "𐀀", "가", "나", null));
		Result current = run(BookGrouping::new, rows, 0);
		Result sql = run(() -> new SqlGrouping(dataSource), rows, 0);
		assertSame(sql, current);
		assertThat(sql.books.get(0).get(0)).isEqualTo(emoji);
		assertThat(sql.books.get(0).get(1)).isEqualTo("😀");
		assertThat(sql.books.get(0).get(2)).isEqualTo("😀");
		assertThat(sql.books.get(1).get(0)).isEqualTo("𐀀");
	}

	/** {@link SqlGrouping#hashMapOrder} gives the order a real {@link HashMap} filled by computeIfAbsent gives. */
	@ParameterizedTest(name = "seed {0}")
	@ValueSource(longs = { 1, 2, 3, 4, 5 })
	void ordersLikeAHashMap(long seed) {
		Random random = new Random(seed);
		int calls = 50 + random.nextInt(5000);
		List<String> keyPool = new ArrayList<>();
		for (int i = 0; i < 1 + random.nextInt(calls); i++) {
			keyPool.add("key " + random.nextInt(1_000_000) + " " + i);
		}
		Map<String, LocalDate[]> map = new HashMap<>();
		Map<String, Integer> firstCall = new HashMap<>();
		int lastSizeBefore = 0;
		for (int call = 0; call < calls; call++) {
			String key = keyPool.get(random.nextInt(keyPool.size()));
			lastSizeBefore = map.size();
			map.computeIfAbsent(key, k -> new LocalDate[0]);
			firstCall.putIfAbsent(key, call);
		}
		List<SqlGrouping.WideKey> keys = new ArrayList<>();
		map.keySet().forEach(key -> keys.add(new SqlGrouping.WideKey(key.hashCode(), firstCall.get(key), key)));
		assertThat(SqlGrouping.hashMapOrder(keys, lastSizeBefore)).extracting(SqlGrouping.WideKey::line)
			.containsExactlyElementsOf(new ArrayList<>(map.keySet()));
	}

	// --- running a grouping -----------------------------------------------------------------------------------------

	record Result(List<List<Object>> books, List<Long> bookOfRow, ImportReport report, Set<String> keys,
			List<Integer> passes) {
	}

	/** The full call sequence of {@link CatalogImporter}; the bibliography map gives an ISBN for some keys. */
	static Result run(Supplier<BookGrouper> groupers, List<Row> rows, long seed) {
		ImportReport report = new ImportReport();
		List<List<Object>> books = new ArrayList<>();
		List<Long> bookOfRow = new ArrayList<>();
		Set<String> keys;
		List<Integer> passes = List.of();
		try (BookGrouper grouping = groupers.get()) {
			rows.forEach(row -> grouping.add(row, report));
			grouping.groupByCodes(report);
			keys = new HashSet<>(grouping.bibliographyKeys());
			grouping.attachBibliography(bibliography(keys, seed), report);
			grouping.mergeSameText(report);
			grouping.forEachBook(book -> {
				books.add(Arrays.asList(book.title(), book.authorDisplay(), book.publisher(), book.titleNorm(),
						book.mainTitleNorm(), book.publisherNorm(), book.publishedOn(), book.isbn13(), book.contentType(),
						book.contributors(), Arrays.toString(book.libraryIds())));
				return 1000L + books.size() * 7L;
			});
			rows.forEach(row -> bookOfRow.add(grouping.bookOf(row)));
			if (grouping instanceof SqlGrouping sql) {
				passes = sql.passes();
			}
		}
		return new Result(books, bookOfRow, report, keys, passes);
	}

	/** ISBNs for about half of the keys, from a small pool so several keys share one, and some are step-② ISBNs. */
	static Map<String, String> bibliography(Set<String> keys, long seed) {
		Map<String, String> isbns = new HashMap<>();
		for (String key : keys) {
			int h = Math.floorMod(key.hashCode() * 31 + (int) seed, 10);
			if (h < 5) {
				isbns.put(key, "97889" + String.format("%08d", h % 3));
			}
			else if (h == 5) {
				isbns.put(key, isbnOf(h % 4));
			}
		}
		return isbns;
	}

	static void assertSame(Result sql, Result current) {
		assertThat(sql.books).containsExactlyElementsOf(current.books);
		assertThat(sql.bookOfRow).containsExactlyElementsOf(current.bookOfRow);
		assertThat(sql.keys).isEqualTo(current.keys);
		assertThat(counts(sql.report)).isEqualTo(counts(current.report));
	}

	static List<Object> counts(ImportReport r) {
		return List.of(r.booksAfterPlatformCode, r.booksAfterKyoboIsbn, r.booksAfterNationalBibliography,
				r.booksAfterSameText, r.sameTextMerged, r.sameTextWideDates, r.sameTextWideDateSamples,
				r.nationalBibliographyIsbnAttached, r.kyoboCodes);
	}

	// --- rows ---------------------------------------------------------------------------------------------------------

	static final Library KYOBO = library(1, "KYOBO_V1");

	static final Library YES24 = library(2, "YES24_V2");

	static final Library PORTAL_A = library(3, "ELIB_PORTAL_V1");

	static final Library PORTAL_B = library(4, "ELIB_PORTAL_V1");

	static final Library BOOKCUBE = library(5, "BOOKCUBE_FX");

	static final List<Library> LIBRARIES = List.of(KYOBO, YES24, PORTAL_A, PORTAL_B, BOOKCUBE);

	static final String[] TITLES = { "작별하지 않는다", "작별하지 않는다 : 특별판", "포털 책", "포털 책(개정판)", "같은 책",
			"Same Book", "same book", "😀 책", "Ａ 책", "한 줄\t제목" };

	static final String[] AUTHORS = { "한강 저", "한강 지음", "가", "가, 나 공저", "나 옮김", null, "", "  " };

	static final String[] PUBLISHERS = { "문학동네", "(주)문학동네", "나", "다", null, " " };

	static final String[] CONTENT_TYPES = { "EBOOK", "AUDIOBOOK", null };

	static final LocalDate[] DATES = { LocalDate.of(2020, 2, 29), LocalDate.of(2022, 2, 28), LocalDate.of(2022, 3, 1),
			LocalDate.of(2015, 6, 1), LocalDate.of(2023, 1, 1), null, null };

	/** Random rows from small pools: shared codes, ISBN chains, ties, same text, missing authors. */
	static List<Row> randomRows(Random random, int count) {
		List<Row> rows = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			Library library = LIBRARIES.get(random.nextInt(LIBRARIES.size()));
			String itemKey = switch (random.nextInt(3)) {
				case 0 -> isbnCode(random.nextInt(40));
				case 1 -> "K" + random.nextInt(300);
				default -> "EBK:" + random.nextInt(300);
			};
			String altCode = random.nextInt(3) == 0 ? isbnCode(random.nextInt(40)) : null;
			rows.add(new Row(library, itemKey, "https://lib.test/" + itemKey, pick(random, CONTENT_TYPES),
					pick(random, TITLES), pick(random, AUTHORS), pick(random, PUBLISHERS), null, pick(random, DATES), null,
					altCode, Instant.parse("2026-10-01T00:00:00Z")));
		}
		return rows;
	}

	static <T> T pick(Random random, T[] values) {
		return values[random.nextInt(values.length)];
	}

	/** A Kyobo-style code whose paper ISBN is {@link #isbnOf}(n). */
	static String isbnCode(int n) {
		return BookGrouperContractTests.kyoboCode("89" + String.format("%07d", n));
	}

	static String isbnOf(int n) {
		return Isbn.fromKyoboCode(isbnCode(n)).isbn13();
	}

	static Row row(Library library, String itemKey, String altCode, String title, String author, String publisher,
			LocalDate date) {
		return new Row(library, itemKey, "https://lib.test/" + itemKey, "EBOOK", title, author, publisher, null, date,
				null, altCode, Instant.parse("2026-10-01T00:00:00Z"));
	}

	static Library library(long id, String platformType) {
		return new Library(id, id, platformType, "lib" + id + ".test", "https://lib" + id + ".test",
				Instant.parse("2026-10-01T00:00:00Z"), false);
	}

}
