package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.BookTexts;
import com.chaekcheck.catalog.CoverUrls;
import com.chaekcheck.catalog.IdBook;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.internal.lists.CoverFiles;
import com.chaekcheck.picks.internal.store.PicksStore;
import com.chaekcheck.picks.internal.store.PicksStore.StoredBook;
import com.chaekcheck.picks.internal.store.PicksStore.StoredField;

/**
 * The shared copies per list (cycle 14 design-delta "캐시", cycle 21 handoff step 4-5): order, fields, our book, the
 * shown name, and the copy before after a failure (C9). Cycle 26: the summary is our book's 책첵 summary and the cover
 * follows ADR-0164 (AC-1, AC-9).
 */
class PicksCacheTests {

	final PicksStore store = mock(PicksStore.class);

	final BookLookup lookup = mock(BookLookup.class);

	final LibraryCatalog libraries = mock(LibraryCatalog.class);

	final BookTexts bookTexts = mock(BookTexts.class);

	final CoverUrls coverUrls = key -> "https://cf.test/covers/" + key + ".jpg";

	final PicksCache cache = new PicksCache(store, lookup, libraries, bookTexts, coverUrls);

	final List<StoredField> fields = new ArrayList<>(List.of(field("librarian", "11", "문학"),
			field("librarian", "6", "인문"), field("librarian", "5", "사회"), field("librarian", "4", "자연"),
			field("librarian", "425", "테마")));

	@BeforeEach
	void stored() {
		given(libraries.lendingKinds()).willReturn(Map.of(1L, false, 2L, false, 7L, false));
		given(store.fields()).willReturn(fields);
		given(store.books(any())).willReturn(List.of());
		given(lookup.byIsbn13(any())).willReturn(List.of());
		given(lookup.byTitleAndAuthor(any())).willReturn(List.of());
		given(lookup.byIds(any())).willReturn(List.of());
	}

	@Test
	void librarianByIsbnFirstThenTitleAndAuthorForTheRest() {
		given(store.books("librarian")).willReturn(List.of(librarian("3", "11", null, "9791124591055", "가", "김가"),
				librarian("2", "11", null, null, "나", "김나"), librarian("1", "6", null, "9788968335273", "다", "김다")));
		given(store.lastSucceededAt()).willReturn(Optional.of(Instant.parse("2026-10-11T20:00:41Z")));
		given(lookup.byIsbn13(any()))
			.willReturn(List.of(new IsbnBook("9791124591055", 100, "가(큰글자)", "가", new long[] { 7 })));
		TitleAuthor na = new TitleAuthor("나", "김나");
		TitleAuthor da = new TitleAuthor("다", "김다");
		given(lookup.byTitleAndAuthor(any())).willReturn(List.of(new TitleAuthorBook(na, 300, "나", "", new long[] { 1 }),
				new TitleAuthorBook(na, 200, "나", "", new long[] { 1 }),
				new TitleAuthorBook(da, 400, "다", "", new long[] { 1, 2 })));

		cache.refresh();

		List<PicksCache.Book> books = cache.snapshot("librarian").categories().getFirst().books();
		assertThat(books).extracting(PicksCache.Book::id, PicksCache.Book::bookId, PicksCache.Book::title,
				PicksCache.Book::listTitle)
			.containsExactly(tuple("3", 100L, "가", null), tuple("2", 200L, "나", null), tuple("1", 400L, "다", null));
		assertThat(cache.snapshot("librarian").fetchedAt()).isEqualTo(Instant.parse("2026-10-11T20:00:41Z"));
	}

	@Test
	void librarianOrdersAndListsByField() { // C14 AC-4, AC-4b
		fields.add(field("librarian", "999", "새분야"));
		fields.add(field("librarian", "1000", "기타"));
		given(store.books("librarian")).willReturn(List.of(librarian("20260922163135829100", "11", "2026-09-22", null, "가", null),
				librarian("20260928084907621100", "6", "2026-09-28", null, "나", null),
				librarian("20260928084907621200", "425", "2026-09-28", null, "다", null),
				librarian("20260801000000000000", "999", null, null, "라", null),
				librarian("20260801000000000001", "1000", null, null, "마", null),
				librarian("20260101000000000000", "11", "2026-01-29", null, "바", null)));

		cache.refresh();

		List<PicksCache.Category> categories = cache.snapshot("librarian").categories();
		assertThat(categories).extracting(PicksCache.Category::code, PicksCache.Category::name)
			.containsExactly(tuple("ALL", "전체"), tuple("11", "문학"), tuple("6", "인문"), tuple("425", "테마"),
					tuple("999", "새분야"), tuple("1000", "기타"));
		// month, then registration date (unknown last), then recomNo as text, latest first
		assertThat(categories.getFirst().books()).extracting(PicksCache.Book::title)
			.containsExactly("다", "나", "가", "마", "라", "바");
		assertThat(categories.getFirst().books().getFirst().recommendedMonth()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	void leedongjinByMonthThenOrderWithTheImportsBooks() { // AC-5, AC-16
		fields.add(field("leedongjin", "foreign_fiction", "외국 소설"));
		fields.add(field("leedongjin", "science", "과학·의학"));
		given(store.books("leedongjin")).willReturn(List.of(
				book(11, "foreign_fiction", 2, "2025-12", "맡겨진 소녀", 501L, 2025),
				book(12, "science", 1, "2026-06", "히스토리아 비테이", 502L, null),
				book(13, "foreign_fiction", 1, "2025-12", "햄릿", 503L, 2025),
				book(14, "science", 1, "2026-01", "인간 제국 쇠망사", 999L, null)));
		given(lookup.byIds(any())).willReturn(List.of(new IdBook(501, "맡겨진 소녀", "다산책방", new long[] { 1 }),
				new IdBook(502, "히스토리아 비테이", "지식서재", new long[] { 2 }),
				new IdBook(503, "햄릿(Hamlet)", "민음사", new long[] { 7 })));

		cache.refresh();

		PicksCache.Snapshot snapshot = cache.snapshot("leedongjin");
		assertThat(snapshot.fetchedAt()).isNull();
		assertThat(snapshot.categories()).extracting(PicksCache.Category::code)
			.containsExactly("ALL", "foreign_fiction", "science");
		// 999 is no longer in the catalog: the list's title, no book
		assertThat(snapshot.categories().getFirst().books())
			.extracting(PicksCache.Book::id, PicksCache.Book::title, PicksCache.Book::listTitle, PicksCache.Book::bookId,
					PicksCache.Book::yearBook)
			.containsExactly(tuple("12", "히스토리아 비테이", null, 502L, null), tuple("14", "인간 제국 쇠망사", null, null, null),
					tuple("13", "햄릿", null, 503L, 2025), tuple("11", "맡겨진 소녀", null, 501L, 2025));
	}

	@Test
	void snuByTableOfContentsWithTheListTitleWhenItDiffers() { // AC-4, AC-16
		fields.add(field("snu100", "korean_lit", "한국문학"));
		fields.add(field("snu100", "foreign_lit", "외국문학"));
		fields.add(field("snu100", "science", "과학기술"));
		given(store.books("snu100")).willReturn(List.of(snu(30, "foreign_lit", 23, "셰익스피어: 햄릿 외 3편", 703L),
				snu(20, "korean_lit", 16, "토지", 702L), snu(10, "korean_lit", 3, "구운몽", 701L)));
		given(lookup.byIds(any())).willReturn(List.of(new IdBook(701, "구운몽", "x", new long[] { 1 }),
				new IdBook(702, "토지. 1(1부 1권)", "x", new long[] { 1 }), new IdBook(703, "햄릿", "x", new long[] { 1 })));

		cache.refresh();

		List<PicksCache.Category> categories = cache.snapshot("snu100").categories();
		assertThat(categories).extracting(PicksCache.Category::code).containsExactly("ALL", "korean_lit", "foreign_lit");
		assertThat(categories.getFirst().books())
			.extracting(PicksCache.Book::tocNo, PicksCache.Book::title, PicksCache.Book::listTitle)
			.containsExactly(tuple(3, "구운몽", null), tuple(16, "토지", null), tuple(23, "햄릿", "셰익스피어: 햄릿 외 3편"));
		assertThat(categories.getFirst().books().getFirst().recommendedMonth()).isNull();
	}

	@Test
	void aFailedListKeepsItsCopyBeforeAndTheOthersGoOn() { // C9
		fields.add(field("snu100", "korean_lit", "한국문학"));
		given(store.books("librarian")).willReturn(List.of(librarian("1", "11", null, null, "가", null)));
		given(store.books("snu100")).willReturn(List.of(snu(1, "korean_lit", 3, "구운몽", null)));
		cache.refresh();
		PicksCache.Snapshot before = cache.snapshot("librarian");
		given(lookup.byTitleAndAuthor(any())).willThrow(new QueryTimeoutException("lock_timeout"));
		given(store.books("snu100")).willReturn(List.of(snu(1, "korean_lit", 3, "구운몽", null),
				snu(2, "korean_lit", 4, "청구야담", null)));

		cache.refresh();

		assertThat(cache.snapshot("librarian")).isSameAs(before);
		assertThat(cache.snapshot("snu100").categories().getFirst().books()).hasSize(2);
	}

	@Test
	void theSummaryIsOurBooksAndTheCoverTheEditionsTheWorksOrTheListsPicture() { // cycle 26 AC-1, AC-9
		fields.add(field("snu100", "korean_lit", "한국문학"));
		given(store.books("snu100")).willReturn(List.of(snu(1, "korean_lit", 1, "구운몽", 701L, "https://img.test/1"),
				snu(2, "korean_lit", 2, "토지", 702L, "https://img.test/2"),
				snu(3, "korean_lit", 3, "청구야담", null, "https://img.test/3"),
				snu(4, "korean_lit", 4, "홍길동전", 704L, null)));
		given(lookup.byIds(any())).willReturn(List.of(new IdBook(701, "구운몽", "x", new long[] { 1 }),
				new IdBook(702, "토지", "x", new long[] { 1 }), new IdBook(704, "홍길동전", "x", new long[] { 1 })));
		given(bookTexts.summaries(any())).willReturn(Map.of(701L, "꿈속에서 부귀영화를 누리는 이야기예요."));
		// 701: its edition's cover or its work's (catalog decides), 702 and 704: none in the catalog
		given(bookTexts.listCoverUrls(any())).willReturn(Map.of(701L, "https://cf.test/covers/" + "a".repeat(40) + ".jpg"));

		cache.refresh();

		assertThat(cache.snapshot("snu100").categories().getFirst().books())
			.extracting(PicksCache.Book::title, PicksCache.Book::coverPath, PicksCache.Book::aiSummary)
			.containsExactly(
					tuple("구운몽", "https://cf.test/covers/" + "a".repeat(40) + ".jpg", "꿈속에서 부귀영화를 누리는 이야기예요."),
					tuple("토지", coverUrls.of(CoverFiles.key("https://img.test/2")), null),
					// a row without our book: the list's picture and no summary
					tuple("청구야담", coverUrls.of(CoverFiles.key("https://img.test/3")), null),
					tuple("홍길동전", null, null));
	}

	@Test
	void theLibrarianListsCoverIsOnCloudFrontToo() { // cycle 26 ADR-0163: no /api/v1/picks/covers/ address any more
		given(store.books("librarian")).willReturn(List.of(new StoredBook(1, "11", 1, "가", null, null,
				LocalDate.of(2026, 10, 1), null, null, null, "1", null, null, "https://www.nl.go.kr/afile/1.jpg", 55L)));

		cache.refresh();

		assertThat(cache.snapshot("librarian").categories().getFirst().books().getFirst().coverPath())
			.isEqualTo(coverUrls.of(CoverFiles.key("https://www.nl.go.kr/afile/1.jpg")));
	}

	@Test
	void nothingStoredOrNoSuchListIsEmpty() {
		cache.refresh();

		assertThat(cache.snapshot("librarian")).isSameAs(PicksCache.Snapshot.EMPTY);
		assertThat(cache.snapshot("leedongjin")).isSameAs(PicksCache.Snapshot.EMPTY);
		assertThat(cache.snapshot("yes24")).isSameAs(PicksCache.Snapshot.EMPTY);
	}

	private static StoredField field(String list, String code, String name) {
		return new StoredField(list, code, name);
	}

	private static StoredBook librarian(String recomNo, String code, String registered, String isbn, String title,
			String author) {
		LocalDate month = recomNo.startsWith("202609") ? LocalDate.of(2026, 10, 1)
				: recomNo.startsWith("202608") ? LocalDate.of(2026, 8, 1)
				: recomNo.startsWith("202601") ? LocalDate.of(2026, 2, 1) : LocalDate.of(2026, 10, 1);
		return new StoredBook(Long.parseLong(recomNo.substring(Math.max(0, recomNo.length() - 6))), code, 1, title,
				author, isbn, month, null, null, null, recomNo, registered == null ? null : LocalDate.parse(registered),
				null, null, null);
	}

	private static StoredBook book(long id, String field, int position, String month, String title, Long bookId,
			Integer yearBook) {
		return new StoredBook(id, field, position, title, null, null, LocalDate.parse(month + "-01"), yearBook,
				"vid" + id, null, null, null, bookId, null, null);
	}

	private static StoredBook snu(long id, String field, int tocNo, String title, Long bookId) {
		return snu(id, field, tocNo, title, bookId, null);
	}

	private static StoredBook snu(long id, String field, int tocNo, String title, Long bookId, String cover) {
		return new StoredBook(id, field, tocNo, title, null, null, null, null, null, tocNo, null, null, bookId, cover,
				null);
	}

}
