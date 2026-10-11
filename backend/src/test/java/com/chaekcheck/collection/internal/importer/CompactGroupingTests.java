package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

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
import java.util.TreeSet;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.chaekcheck.catalog.normalize.Isbn;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Library;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * The grouping {@code compact} (cycle 11, ADR-0079): the contract of every grouping, its 128-bit key table, and the
 * same result as {@link BookGrouping} for random rows.
 */
class CompactGroupingTests {

	@Test
	void followsTheContract() {
		BookGrouperContractTests.assertContract(CompactGrouping::new);
		BookGrouperContractTests.assertContract(() -> new CompactGrouping(2, true));
	}

	@Test
	void keyTableGrowsAndFindsEveryKey() {
		CompactGrouping.KeyTable table = new CompactGrouping.KeyTable(2);
		for (int i = 0; i < 100_000; i++) {
			assertThat(table.add("SHARED\u0001" + i)).isEqualTo(i);
		}
		assertThat(table.size()).isEqualTo(100_000);
		for (int i = 0; i < 100_000; i++) {
			assertThat(table.find("SHARED\u0001" + i)).isEqualTo(i);
			assertThat(table.add("SHARED\u0001" + i)).isEqualTo(i);
			assertThat(table.find("LIB1\u0001" + i)).isEqualTo(-1);
		}
		assertThat(table.size()).isEqualTo(100_000);
	}

	@Test
	void keyTableProbesPastKeysInTheSameSlot() {
		CompactGrouping.KeyTable table = new CompactGrouping.KeyTable(8);
		// slot = low bits of the first hash: 1, 9 and 17 all land in slot 1 of 8
		assertThat(table.add(1L, 100L)).isEqualTo(0);
		assertThat(table.add(9L, 100L)).isEqualTo(1);
		assertThat(table.add(1L, 200L)).isEqualTo(2);
		assertThat(table.add(17L, 300L)).isEqualTo(3);
		assertThat(table.add(2L, 400L)).isEqualTo(4);
		assertThat(table.find(1L, 100L)).isEqualTo(0);
		assertThat(table.find(9L, 100L)).isEqualTo(1);
		assertThat(table.find(1L, 200L)).isEqualTo(2);
		assertThat(table.find(17L, 300L)).isEqualTo(3);
		assertThat(table.find(2L, 400L)).isEqualTo(4);
		assertThat(table.find(25L, 100L)).isEqualTo(-1);
		assertThat(table.find(1L, 300L)).isEqualTo(-1);
		// past 3/4 full the table grows; everything is still found
		assertThat(table.add(1L, 500L)).isEqualTo(5);
		assertThat(table.add(1L, 600L)).isEqualTo(6);
		assertThat(table.add(1L, 100L)).isEqualTo(0);
		assertThat(table.find(17L, 300L)).isEqualTo(3);
		assertThat(table.find(1L, 600L)).isEqualTo(6);
		assertThat(table.find(33L, 600L)).isEqualTo(-1);
	}

	@Test
	void mapShapeGivesTheOrderOfComputeIfAbsent() {
		int treeKeys = 0;
		for (long seed = 0; seed < 30; seed++) {
			Random random = new Random(seed);
			int distinct = 1 + random.nextInt(seed < 20 ? 3000 : 120);
			List<String> pool = new ArrayList<>();
			for (int i = 0; i < distinct; i++) {
				// "Aa" and "BB" have the same hashCode: some keys share one, so some bins become trees
				pool.add(random.nextInt(4) == 0 ? collidingText(random) : "key " + random.nextInt(1_000_000));
			}
			Map<String, Integer> map = new HashMap<>();
			CompactGrouping.MapShape shape = new CompactGrouping.MapShape();
			for (int call = 0; call < distinct * 2; call++) {
				String key = pool.get(random.nextInt(pool.size()));
				boolean added = !map.containsKey(key);
				int index = map.size();
				map.computeIfAbsent(key, k -> index);
				shape.call(key.hashCode(), added);
			}
			long[] sorted = map.values().stream().mapToLong(shape::sortKey).sorted().toArray();
			List<Integer> expected = new ArrayList<>(map.values());
			for (int p = 0; p < sorted.length; p++) {
				if (shape.listBin((int) (sorted[p] >>> 32))) {
					assertThat(CompactGrouping.MapShape.index(sorted[p])).as("seed %d position %d", seed, p)
						.isEqualTo(expected.get(p));
				}
				else {
					treeKeys++;
				}
			}
		}
		assertThat(treeKeys).isPositive();
	}

	private static String collidingText(Random random) {
		StringBuilder text = new StringBuilder();
		for (int i = 0; i < 4; i++) {
			text.append(random.nextBoolean() ? "Aa" : "BB");
		}
		return text.toString();
	}

	@ParameterizedTest
	@ValueSource(longs = { 1, 2, 3, 42, 2026 })
	void givesWhatTheCurrentGroupingGives(long seed) {
		List<Row> rows = randomRows(new Random(seed), 4000);
		Map<String, String> isbns = new HashMap<>();
		Result current = run(BookGrouping::new, rows, isbns, new Random(seed));
		assertThat(current.report.sameTextMerged).isPositive();
		assertThat(current.report.nationalBibliographyIsbnAttached).isPositive();
		assertThat(current.report.sameTextWideDateSamples).hasSize(40);
		assertThat(current.report.booksAfterKyoboIsbn).isLessThan(current.report.booksAfterPlatformCode);

		for (Supplier<BookGrouper> compact : List.<Supplier<BookGrouper>>of(CompactGrouping::new,
				() -> new CompactGrouping(2, true))) {
			Result result = run(compact, rows, isbns, new Random(seed));
			assertThat(result.keys).isEqualTo(current.keys);
			assertThat(result.books).isEqualTo(current.books);
			assertThat(result.bookOf).isEqualTo(current.bookOf);
			assertThat(counts(result.report)).isEqualTo(counts(current.report));
		}
	}

	@Test
	void givesWhatTheCurrentGroupingGivesForFewRows() {
		for (long seed = 100; seed < 140; seed++) {
			List<Row> rows = randomRows(new Random(seed), 30 + (int) (seed % 7) * 25);
			Result current = run(BookGrouping::new, rows, new HashMap<>(), new Random(seed));
			for (Supplier<BookGrouper> compact : List.<Supplier<BookGrouper>>of(CompactGrouping::new,
					() -> new CompactGrouping(2, true))) {
				Result result = run(compact, rows, new HashMap<>(), new Random(seed));
				assertThat(result.books).isEqualTo(current.books);
				assertThat(result.bookOf).isEqualTo(current.bookOf);
				assertThat(counts(result.report)).isEqualTo(counts(current.report));
			}
		}
	}

	private record Result(Set<String> keys, List<List<Object>> books, List<Long> bookOf, ImportReport report) {
	}

	/**
	 * The whole call sequence; the national bibliography gives an ISBN to about half the keys (Kyobo ISBNs of the
	 * rows, new ones, and one text that is not 13 digits).
	 */
	private static Result run(Supplier<BookGrouper> groupers, List<Row> rows, Map<String, String> isbns,
			Random random) {
		ImportReport report = new ImportReport();
		List<List<Object>> books = new ArrayList<>();
		List<Long> bookOf = new ArrayList<>();
		Set<String> keys;
		try (BookGrouper grouping = groupers.get()) {
			for (Row row : rows) {
				grouping.add(row, report);
			}
			grouping.groupByCodes(report);
			keys = new HashSet<>(grouping.bibliographyKeys());
			if (isbns.isEmpty()) {
				List<String> pool = new ArrayList<>(List.of("9791100000001", "9791100000002", "odd isbn"));
				for (String code : KYOBO_CODES) {
					String isbn = Isbn.fromKyoboCode(code).isbn13();
					if (isbn != null) {
						pool.add(isbn);
					}
				}
				for (String key : new TreeSet<>(keys)) {
					if (random.nextBoolean()) {
						isbns.put(key, pool.get(random.nextInt(pool.size())));
					}
				}
			}
			grouping.attachBibliography(isbns, report);
			grouping.mergeSameText(report);
			grouping.forEachBook(book -> {
				books.add(Arrays.asList(book.title(), book.authorDisplay(), book.publisher(), book.titleNorm(),
						book.mainTitleNorm(), book.publisherNorm(), book.publishedOn(), book.isbn13(),
						book.contentType(), book.contributors(), Arrays.toString(book.libraryIds())));
				return 1000L + books.size() * 7L;
			});
			for (Row row : rows) {
				bookOf.add(grouping.bookOf(row));
			}
		}
		return new Result(keys, books, bookOf, report);
	}

	private static List<Object> counts(ImportReport report) {
		return Arrays.asList(report.booksAfterPlatformCode, report.booksAfterKyoboIsbn,
				report.booksAfterNationalBibliography, report.booksAfterSameText, report.sameTextMerged,
				report.sameTextWideDates, report.sameTextWideDateSamples, report.nationalBibliographyIsbnAttached,
				report.kyoboCodes);
	}

	private static final List<Library> LIBRARIES = List.of(library(1, "KYOBO_V1"), library(2, "YES24_V2"),
			library(3, "KYOBO_SUBSCRIPTION_V1"), library(4, "ELIB_PORTAL_V1"), library(5, "ELIB_PORTAL_V1"),
			library(6, "BOOKCUBE_V1"), library(7, "YES24_V3"), library(8, "GANGNAM_V1"));

	/** Kyobo-style codes from a small pool (so ISBN chains occur), with a bad check digit and a non-Korean one. */
	private static final List<String> KYOBO_CODES = kyoboCodes();

	private static List<String> kyoboCodes() {
		List<String> codes = new ArrayList<>();
		for (int i = 0; i < 40; i++) {
			codes.add(BookGrouperContractTests.kyoboCode("89" + String.format("%07d", i * 37)));
		}
		for (int i = 0; i < 10; i++) {
			codes.add(BookGrouperContractTests.kyoboCode("11" + String.format("%07d", i * 11)));
		}
		String good = BookGrouperContractTests.kyoboCode("890000123");
		codes.add(good.substring(0, 12) + (char) ('0' + (good.charAt(12) - '0' + 1) % 10));
		codes.add(BookGrouperContractTests.kyoboCode("120000001"));
		return codes;
	}

	private static final String[] TITLES = { "가 책", "나 책", "다 책(개정판)", "라 책 : 부제", "마 책 - 시리즈 1", "Alpha",
			"alpha", "ALPHA ", "바 책", "사 책", "아 책", "자 책", "차 책", "카 책", "타 책", "파 책", "하 책", "가 책 (특별판)",
			"나 책 : 다른 부제", "  ", "거 책", "너 책", "더 책", "러 책" };

	private static final String[] AUTHORS = { "한강 저", "한강 지음", "김가 저 ; 이나 옮김", "이나 옮김", "박다", null, " ",
			"최라 글, 정마 그림", "Kim" };

	private static final String[] PUBLISHERS = { "문학동네", "(주)문학동네", "민음사", "창비", null, "" };

	private static final String[] TYPES = { "EBOOK", "EBOOK", "EBOOK", "AUDIOBOOK", null, " " };

	private static List<Row> randomRows(Random random, int count) {
		List<Row> rows = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		LocalDate[] dates = new LocalDate[30];
		for (int i = 0; i < dates.length; i++) {
			dates[i] = LocalDate.of(1995 + random.nextInt(30), 1 + random.nextInt(12), 1 + random.nextInt(28));
		}
		while (rows.size() < count) {
			Library library = LIBRARIES.get(random.nextInt(LIBRARIES.size()));
			int kind = random.nextInt(10);
			String itemKey = kind < 4 ? KYOBO_CODES.get(random.nextInt(KYOBO_CODES.size()))
					: kind < 7 ? "EBK:" + random.nextInt(count / 4) : "K" + random.nextInt(count / 3);
			if (!seen.add(library.serviceId() + "/" + itemKey)) {
				continue;
			}
			int alt = random.nextInt(6);
			String altCode = alt == 0 ? KYOBO_CODES.get(random.nextInt(KYOBO_CODES.size())) : alt == 1 ? "X1" : null;
			String title = TITLES[random.nextInt(TITLES.length)];
			LocalDate date = random.nextInt(5) == 0 ? null : dates[random.nextInt(dates.length)];
			rows.add(new Row(library, itemKey, "https://lib.test/" + itemKey, TYPES[random.nextInt(TYPES.length)],
					title, AUTHORS[random.nextInt(AUTHORS.length)], PUBLISHERS[random.nextInt(PUBLISHERS.length)],
					null, date, null, altCode, Instant.parse("2026-10-01T00:00:00Z")));
		}
		return rows;
	}

	private static Library library(long id, String platformType) {
		return new Library(id, id, platformType, "lib" + id + ".test", "https://lib" + id + ".test",
				Instant.parse("2026-10-01T00:00:00Z"), false);
	}

}
