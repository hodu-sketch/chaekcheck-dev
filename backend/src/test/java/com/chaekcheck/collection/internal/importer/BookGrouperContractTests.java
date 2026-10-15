package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Isbn;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Library;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * What every way of grouping must give for the same rows (cycle 11, ADR-0079, {@link BookGrouper}): the books of
 * steps ①~④, the counts per step, the most common value on a tie, the order of the first row, and each row's book.
 * Every grouping runs through the same expectations ({@link #assertContract}), with step ④'s cycle 12 rule
 * (ADR-0082, {@link #sameTextRows}).
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
		assertCodeSteps(groupers);
		assertSameTextRules(groupers);
		assertSupplierCodeRules(groupers);
	}

	static final Library BOOKCUBE_A = library(5, "BOOKCUBE_FX");

	static final Library BOOKCUBE_B = library(6, "BOOKCUBE_FX");

	/** Supplier families of the cycle 19 tests (ADR-0114): Y2Books and 와이투북스 are one, 교보 as in the rules. */
	static final ImportRules CODE_RULES = new ImportRules(Map.of(), Map.of(ImportRules.supplierName("Y2Books 전자책"),
			"y2books", ImportRules.supplierName("와이투북스"), "y2books", ImportRules.supplierName("교보 전자책"), "kyobo",
			ImportRules.supplierName("교보문고"), "kyobo"));

	/** Rows of the cycle 19 supplier code rules; the comment names what each case checks. */
	static List<Row> supplierCodeRows() {
		List<Row> rows = new ArrayList<>();
		// 0, 1: BOOKCUBE alt code = Kyobo item key, Y2Books 전자책 = 와이투북스 → one book (AC-2), though the texts differ
		rows.add(coded(BOOKCUBE_A, "B1", "1210492004114", "Y2Books 전자책", "(부를 끌어 당기는) 백만장자 마인드", "김근아 저자", "율리시즈"));
		rows.add(coded(KYOBO, "1210492004114", null, "와이투북스", "백만장자 마인드", "모리세 시게토모", "율리시즈"));
		// 2, 3: the same 9-digit number from BOOKCUBE's own supplier and from YES24 → apart
		rows.add(coded(BOOKCUBE_A, "190700059", "190700059", "(주)북큐브네트웍스", "북큐브 책", "갑", "을"));
		rows.add(coded(YES24, "190700059", null, "YES24", "예스 책", "병", "정"));
		// 4, 5: no supplier → apart
		rows.add(coded(BOOKCUBE_A, "B7", "777", null, "공급사 없는 책 하나", "무", "기"));
		rows.add(coded(BOOKCUBE_B, "B7", "777", null, "공급사 없는 책 둘", "경", "신"));
		// 6, 7: one code, two ISBNs (item key of the BOOKCUBE row gives another one): joined, counted as a conflict;
		// the book's ISBN is an ISBN, never the code
		rows.add(coded(KYOBO, kyoboCode("890000101"), null, "교보문고", "코드 책 하나", "임", "계"));
		rows.add(coded(BOOKCUBE_B, kyoboCode("890000102"), kyoboCode("890000101"), "교보 전자책", "코드 책 둘", "갑을", "병정"));
		// 8: the same code under 기타 → no key
		rows.add(coded(BOOKCUBE_B, "B8", "1210492004114", "기타 전자책", "기타 책", "무기", "경신"));
		return rows;
	}

	/** Step ②'s supplier code keys (cycle 19, ADR-0114) on {@link #supplierCodeRows()}. */
	static void assertSupplierCodeRules(Supplier<BookGrouper> groupers) {
		ImportReport report = new ImportReport();
		List<BookGrouper.Book> books = new ArrayList<>();
		List<Long> bookOfRow = new ArrayList<>();
		try (BookGrouper grouping = groupers.get()) {
			for (Row row : supplierCodeRows()) {
				grouping.add(row, report);
			}
			grouping.groupByCodes(report);
			grouping.attachBibliography(Map.of(), report);
			grouping.mergeSameText(report);
			grouping.forEachBook(book -> {
				books.add(book);
				return books.size() * 100L;
			});
			for (Row row : supplierCodeRows()) {
				bookOfRow.add(grouping.bookOf(row));
			}
		}
		assertThat(bookOfRow.get(0)).isEqualTo(bookOfRow.get(1));
		assertThat(bookOfRow.get(2)).isNotEqualTo(bookOfRow.get(3));
		assertThat(bookOfRow.get(4)).isNotEqualTo(bookOfRow.get(5));
		assertThat(bookOfRow.get(6)).isEqualTo(bookOfRow.get(7));
		assertThat(bookOfRow.get(8)).isNotEqualTo(bookOfRow.get(0));
		assertThat(books).hasSize(7);
		assertThat(report.supplierCodeJoins).isEqualTo(2);
		assertThat(report.codeJoinedIsbnConflicts).isEqualTo(1);
		BookGrouper.Book conflict = books.get((int) (bookOfRow.get(6) / 100) - 1);
		assertThat(conflict.isbn13()).isEqualTo(Isbn.fromKyoboCode(kyoboCode("890000101")).isbn13());
		BookGrouper.Book y2 = books.get((int) (bookOfRow.get(0) / 100) - 1);
		assertThat(y2.libraryIds()).containsExactly(1, 5);
		assertThat(y2.isbn13()).isNull();
	}

	private static Row coded(Library library, String itemKey, String altCode, String supplier, String title,
			String author, String publisher) {
		return new Row(library, itemKey, "https://lib.test/" + itemKey, "EBOOK", title, author, publisher, null, null,
				supplier, altCode, Instant.parse("2026-10-01T00:00:00Z"), CollectedCatalog.supplierCodeKey(
						library.platformType(), itemKey, altCode, CODE_RULES.family(supplier)));
	}

	/** Steps ①~④ on {@link #rows()}. */
	static void assertCodeSteps(Supplier<BookGrouper> groupers) {
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

	static final String NATIONAL_ISBN = "9788900000900";

	/**
	 * Step ④'s cycle 12 cases (spec R1~R4), one unit each (portal codes are not shared), so steps ①~③ join nothing:
	 * ISBNs from the alt codes, and one from the national bibliography.
	 */
	static List<Row> sameTextRows() {
		List<Row> rows = new ArrayList<>();
		// R1: two editions, each in its own library (AC-1)
		rows.add(row(PORTAL_A, "EBK:0", isbnCode(1), "상법총칙 상행위", "가", "박영사", LocalDate.of(2014, 2, 1)));
		rows.add(row(PORTAL_B, "EBK:1", isbnCode(2), "상법총칙 상행위", "가", "박영사", LocalDate.of(2020, 2, 1)));
		// R1-3: A (ISBN), B (none), C (another ISBN): three books
		rows.add(row(PORTAL_A, "EBK:2", isbnCode(3), "셋 책", "가", "나", null));
		rows.add(row(PORTAL_A, "EBK:3", null, "셋 책", "가", "나", null));
		rows.add(row(PORTAL_A, "EBK:4", isbnCode(4), "셋 책", "가", "나", null));
		// A, B1, B2, C: three books, B1 and B2 one
		rows.add(row(PORTAL_A, "EBK:5", isbnCode(5), "넷 책", "가", "나", null));
		rows.add(row(PORTAL_A, "EBK:6", null, "넷 책", "가", "나", null));
		rows.add(row(PORTAL_B, "EBK:7", null, "넷 책", "가", "나", null));
		rows.add(row(PORTAL_A, "EBK:8", isbnCode(6), "넷 책", "가", "나", null));
		// one ISBN and none: one book, its dates 15 years apart
		rows.add(row(PORTAL_A, "EBK:9", isbnCode(7), "하나 책", "가", "나", LocalDate.of(2000, 1, 1)));
		rows.add(row(PORTAL_B, "EBK:10", null, "하나 책", "가", "나", LocalDate.of(2015, 1, 1)));
		// step ③'s ISBN counts too: B gets another ISBN from the national bibliography
		rows.add(row(PORTAL_A, "EBK:11", isbnCode(8), "국가 책", "가", "나", null));
		rows.add(row(PORTAL_A, "EBK:12", null, "국가 책", "가", "나", null));
		// R2: placeholders are no value (AC-2)
		rows.add(row(PORTAL_A, "EBK:13", null, "햄릿", "기타", "기타", null));
		rows.add(row(PORTAL_B, "EBK:14", null, "햄릿", "기타", "기타", null));
		// R2-4: a placeholder first author, then the real one
		rows.add(row(PORTAL_A, "EBK:15", null, "햄릿", "기타, 셰익스피어", "민음사", null));
		rows.add(row(PORTAL_B, "EBK:16", null, "햄릿", "셰익스피어", "민음사", null));
		// R3: only the subtitle differs (AC-3); dates 10 years apart
		rows.add(row(PORTAL_A, "EBK:17", null, "백만장자 마인드", "가", "율리시즈", LocalDate.of(2010, 5, 1)));
		rows.add(row(PORTAL_B, "EBK:18", null, "백만장자 마인드 - 부를 끌어 당기는", "가", "율리시즈", LocalDate.of(2020, 5, 1)));
		// R3-2: other volumes (AC-4)
		rows.add(row(PORTAL_A, "EBK:19", null, "오개념 탈출 프로젝트 - 수학 1", "가", "나", null));
		rows.add(row(PORTAL_B, "EBK:20", null, "오개념 탈출 프로젝트 - 수학 2", "가", "나", null));
		// three subtitles one library holds side by side: one book each, the title without one apart (user decision
		// 2026-10-14)
		rows.add(row(PORTAL_A, "EBK:21", null, "영단어 무한 지배자 : 고등편", "가", "다", null));
		rows.add(row(PORTAL_A, "EBK:22", null, "영단어 무한 지배자 : 중등편", "가", "다", null));
		rows.add(row(PORTAL_A, "EBK:23", null, "영단어 무한 지배자 : 초등편", "가", "다", null));
		rows.add(row(PORTAL_B, "EBK:24", null, "영단어 무한 지배자", "가", "다", null));
		// two subtitles no library holds side by side: one book
		rows.add(row(PORTAL_A, "EBK:25", null, "갈등 책 - 에세이", "가", "다", null));
		rows.add(row(PORTAL_B, "EBK:26", null, "갈등 책 - 양장", "가", "다", null));
		return rows;
	}

	static void assertSameTextRules(Supplier<BookGrouper> groupers) {
		ImportReport report = new ImportReport();
		List<BookGrouper.Book> books = new ArrayList<>();
		List<Long> bookOfRow = new ArrayList<>();
		try (BookGrouper grouping = groupers.get()) {
			for (Row row : sameTextRows()) {
				grouping.add(row, report);
			}
			grouping.groupByCodes(report);
			String nationalKey = BookGrouper.key("국가책", "가", "나");
			assertThat(grouping.bibliographyKeys()).contains(nationalKey);
			grouping.attachBibliography(Map.of(nationalKey, NATIONAL_ISBN), report);
			grouping.mergeSameText(report);
			grouping.forEachBook(book -> {
				books.add(book);
				return books.size() * 100L;
			});
			for (Row row : sameTextRows()) {
				bookOfRow.add(grouping.bookOf(row));
			}
		}

		assertThat(report.booksAfterPlatformCode).isEqualTo(27);
		assertThat(report.booksAfterKyoboIsbn).isEqualTo(27);
		assertThat(report.nationalBibliographyIsbnAttached).isEqualTo(1);
		assertThat(report.booksAfterNationalBibliography).isEqualTo(27);
		assertThat(report.booksAfterSameText).isEqualTo(22);
		assertThat(report.sameTextMerged).isEqualTo(5);
		// merge keys minus keys: 상법 +1, 셋 +2, 넷 +2, 국가 +1
		assertThat(report.sameTextIsbnSplit).isEqualTo(6);
		// 영단어: three subtitles and the title without one
		assertThat(report.sameTextSubtitleSplit).isEqualTo(3);
		assertThat(report.sameTextPlaceholderNoKey).isEqualTo(2);
		// in the order of the books' first groups
		assertThat(report.sameTextWideDates).isEqualTo(2);
		assertThat(report.sameTextWideDateSamples).containsExactly("2000-01-01~2015-01-01 하나 책 / 가 / 나",
				"2010-05-01~2020-05-01 백만장자 마인드 / 가 / 율리시즈");

		assertThat(books).extracting(BookGrouper.Book::title)
			.containsExactly("상법총칙 상행위", "상법총칙 상행위", "셋 책", "셋 책", "셋 책", "넷 책", "넷 책", "넷 책", "하나 책", "국가 책",
					"국가 책", "햄릿", "햄릿", "햄릿", "백만장자 마인드", "오개념 탈출 프로젝트 - 수학 1", "오개념 탈출 프로젝트 - 수학 2",
					"영단어 무한 지배자 : 고등편", "영단어 무한 지배자 : 중등편", "영단어 무한 지배자 : 초등편", "영단어 무한 지배자",
					"갈등 책 - 양장");
		assertThat(bookOfRow).containsExactly(100L, 200L, 300L, 400L, 500L, 600L, 700L, 700L, 800L, 900L, 900L, 1000L,
				1100L, 1200L, 1300L, 1400L, 1400L, 1500L, 1500L, 1600L, 1700L, 1800L, 1900L, 2000L, 2100L, 2200L, 2200L);
		// each edition with its own library and ISBN
		assertThat(books.get(0).libraryIds()).containsExactly(3);
		assertThat(books.get(1).libraryIds()).containsExactly(4);
		assertThat(books.get(0).isbn13()).isEqualTo(isbnOf(1));
		assertThat(books.get(1).isbn13()).isEqualTo(isbnOf(2));
		assertThat(books.get(3).isbn13()).isNull();
		assertThat(books.get(6).isbn13()).isNull();
		assertThat(books.get(6).libraryIds()).containsExactly(3, 4);
		assertThat(books.get(8).isbn13()).isEqualTo(isbnOf(7));
		assertThat(books.get(8).libraryIds()).containsExactly(3, 4);
		assertThat(books.get(9).isbn13()).isEqualTo(isbnOf(8));
		assertThat(books.get(10).isbn13()).isEqualTo(NATIONAL_ISBN);
		// the placeholders stay for display (R2-3)
		assertThat(books.get(11).authorDisplay()).isEqualTo("기타");
		assertThat(books.get(11).publisher()).isEqualTo("기타");
		assertThat(books.get(11).contributors()).extracting(Contributor::name).containsExactly("기타");
		assertThat(books.get(13).libraryIds()).containsExactly(3, 4);
	}

	/** A Kyobo-style code whose paper ISBN is {@link #isbnOf}(n). */
	static String isbnCode(int n) {
		return kyoboCode("8900001" + String.format("%02d", n));
	}

	static String isbnOf(int n) {
		return Isbn.fromKyoboCode(isbnCode(n)).isbn13();
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
		return row(library, itemKey, altCode, title, author, publisher, null);
	}

	private static Row row(Library library, String itemKey, String altCode, String title, String author,
			String publisher, LocalDate date) {
		return new Row(library, itemKey, "https://lib.test/" + itemKey, "EBOOK", title, author, publisher, null, date,
				null, altCode, Instant.parse("2026-10-01T00:00:00Z"));
	}

}
