package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.chaekcheck.collection.internal.importer.CollectedCatalog.Library;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * What every way of grouping must give for the same rows (cycle 11, ADR-0079, {@link BookGrouper}): the books of
 * steps ①~④, the counts per step, the most common value on a tie, the order of the first row, and each row's book.
 * Every grouping runs through the same expectations ({@link #assertContract}).
 */
class BookGrouperContractTests {

	static final Library KYOBO = library(1, "KYOBO_V1");

	static final Library YES24 = library(2, "YES24_V2");

	static final Library PORTAL_A = library(3, "ELIB_PORTAL_V1");

	static final Library PORTAL_B = library(4, "ELIB_PORTAL_V1");

	static Stream<Arguments> groupings() {
		return Stream.of(Arguments.of("objects", (Supplier<BookGrouper>) BookGrouping::new));
	}

	/** Rows in reading order; the comment names the book each belongs to. */
	static List<Row> rows() {
		String farewell = kyoboCode("895468215");
		List<Row> rows = new ArrayList<>();
		// book 1: ① one shared code in Kyobo and YES24, ② a portal row whose alt code gives the same ISBN
		rows.add(row(KYOBO, farewell, null, "작별하지 않는다", "한강 저", "문학동네"));
		rows.add(row(YES24, farewell, null, "작별하지 않는다", "한강 저", "문학동네"));
		rows.add(row(PORTAL_A, "EBK:1", farewell, "작별하지 않는다 : 특별판", "한강 지음", "(주)문학동네"));
		// book 2: the same portal code in two portals stays apart in ①, ④ joins the same text
		rows.add(row(PORTAL_A, "EBK:2", null, "포털 책", "가", "나"));
		rows.add(row(PORTAL_B, "EBK:2", null, "포털 책", "가", "나"));
		// book 3: an edition mark in the title stays a book of its own
		rows.add(row(PORTAL_B, "EBK:3", null, "포털 책(개정판)", "가", "나"));
		// books 4 and 5: no author, never joined by text
		rows.add(row(PORTAL_A, "EBK:4", null, "저자 없음", null, "나"));
		rows.add(row(PORTAL_B, "EBK:4", null, "저자 없음", null, "나"));
		// book 6: one title each: the tie goes to the smaller value
		rows.add(row(KYOBO, "K9", null, "나 제목", "다", "라"));
		rows.add(row(YES24, "K9", null, "가 제목", "다", "라"));
		// book 7: a unit with two ISBNs joins through the second (not its smaller one) the unit having it; the book's
		// ISBN is then the one most rows of the whole group have
		String a = kyoboCode("890000001");
		String b = kyoboCode("890000002");
		rows.add(row(KYOBO, "K20", a, "같은 책", "마", "바"));
		rows.add(row(YES24, "K20", b, "같은 책", "마", "바"));
		rows.add(row(PORTAL_A, "EBK:20", b, "같은 책", "마", "바"));
		return rows;
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("groupings")
	void givesTheSameBooks(String name, Supplier<BookGrouper> groupers) {
		assertContract(groupers);
	}

	/** The expectations, for the test classes of the other groupings too. */
	static void assertContract(Supplier<BookGrouper> groupers) {
		ImportReport report = new ImportReport();
		List<BookGrouper.Book> books = new ArrayList<>();
		Map<Row, Long> bookOfRow = new LinkedHashMap<>();
		try (BookGrouper grouping = groupers.get()) {
			for (Row row : rows()) {
				grouping.add(row, report);
			}
			grouping.groupByCodes(report);
			assertThat(grouping.bibliographyKeys()).isNotEmpty()
				.noneMatch(key -> key.contains("작별"))
				.noneMatch(key -> key.contains("같은"))
				.noneMatch(key -> key.contains("저자 없음"));
			grouping.attachBibliography(Map.of(), report);
			grouping.mergeSameText(report);
			grouping.forEachBook(book -> {
				books.add(book);
				return books.size() * 100L;
			});
			for (Row row : rows()) {
				bookOfRow.put(row, grouping.bookOf(row));
			}
		}

		assertThat(report.booksAfterPlatformCode).isEqualTo(10);
		assertThat(report.booksAfterKyoboIsbn).isEqualTo(8);
		assertThat(report.booksAfterNationalBibliography).isEqualTo(8);
		assertThat(report.booksAfterSameText).isEqualTo(7);
		assertThat(report.sameTextMerged).isEqualTo(1);
		assertThat(report.kyoboCodes).containsEntry("ISBN", 6L);

		assertThat(books).extracting(BookGrouper.Book::title)
			.containsExactly("작별하지 않는다", "포털 책", "포털 책(개정판)", "저자 없음", "저자 없음", "가 제목", "같은 책");
		assertThat(books.get(0).isbn13()).isEqualTo("9788954682152");
		assertThat(books.get(0).libraryIds()).containsExactly(1, 2, 3);
		assertThat(books.get(0).authorDisplay()).isEqualTo("한강 저");
		assertThat(books.get(1).libraryIds()).containsExactly(3, 4);
		assertThat(books.get(1).isbn13()).isNull();
		assertThat(books.get(6).isbn13()).isEqualTo("9788900000023");
		assertThat(books.get(6).libraryIds()).containsExactly(1, 2, 3);

		assertThat(bookOfRow.values()).containsExactly(100L, 100L, 100L, 200L, 200L, 300L, 400L, 500L, 600L, 600L, 700L,
				700L, 700L);
	}

	/** {@code 480} + t + the ISBN-13 check digit of {@code 978} + t ({@code Isbn.fromKyoboCode}). */
	static String kyoboCode(String t) {
		String body = "978" + t;
		int sum = 0;
		for (int i = 0; i < 12; i++) {
			sum += (body.charAt(i) - '0') * (i % 2 == 0 ? 1 : 3);
		}
		return "480" + t + (10 - sum % 10) % 10;
	}

	private static Library library(long id, String platformType) {
		return new Library(id, id, platformType, "lib" + id + ".test", "https://lib" + id + ".test",
				Instant.parse("2026-10-01T00:00:00Z"), false);
	}

	private static Row row(Library library, String itemKey, String altCode, String title, String author,
			String publisher) {
		return new Row(library, itemKey, "https://lib.test/" + itemKey, "EBOOK", title, author, publisher, null, null,
				null, altCode, Instant.parse("2026-10-01T00:00:00Z"));
	}

}
