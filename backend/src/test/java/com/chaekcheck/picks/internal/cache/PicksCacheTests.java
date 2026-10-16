package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.internal.store.PicksStore;

/** Matching, choosing, ordering and keeping the copy before (plan cycle 14 step 5; ADR-0090, AC-4, AC-4b, AC-6). */
class PicksCacheTests {

	final PicksStore store = mock(PicksStore.class);

	final BookLookup lookup = mock(BookLookup.class);

	final LibraryCatalog libraries = mock(LibraryCatalog.class);

	final PicksCache cache = new PicksCache(store, lookup, libraries);

	@BeforeEach
	void enabledLibraries() {
		given(libraries.lendingKinds()).willReturn(Map.of(1L, false, 2L, false, 7L, false));
	}

	@Test
	void isbnFirstThenTitleAndAuthorForTheRest() {
		given(store.readPicks()).willReturn(List.of(pick("3", "11", null, "9791124591055", "가", "김가"),
				pick("2", "11", null, null, "나", "김나"), pick("1", "6", null, "9788968335273", "다", "김다")));
		given(store.lastSucceededAt()).willReturn(Optional.of(Instant.parse("2026-10-11T20:00:41Z")));
		given(lookup.byIsbn13(any()))
			.willReturn(List.of(new IsbnBook("9791124591055", 100, "가", "가", new long[] { 7 })));
		TitleAuthor na = new TitleAuthor("나", "김나");
		TitleAuthor da = new TitleAuthor("다", "김다");
		given(lookup.byTitleAndAuthor(any())).willReturn(List.of(new TitleAuthorBook(na, 300, "나", "", new long[] { 1 }),
				new TitleAuthorBook(na, 200, "나", "", new long[] { 1 }),
				new TitleAuthorBook(da, 400, "다", "", new long[] { 1, 2 })));

		cache.refresh();

		List<PicksCache.Book> books = cache.snapshot().categories().getFirst().books();
		assertThat(books).extracting(PicksCache.Book::recomNo, PicksCache.Book::bookId)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("3", 100L), org.assertj.core.groups.Tuple.tuple("2", 200L),
					org.assertj.core.groups.Tuple.tuple("1", 400L));
		assertThat(cache.snapshot().fetchedAt()).isEqualTo(Instant.parse("2026-10-11T20:00:41Z"));
	}

	@Test
	void ordersAndListsByField() { // AC-4, AC-4b
		given(store.readPicks()).willReturn(List.of(pick("20260922163135829100", "11", "2026-09-22", null, "가", null),
				pick("20260928084907621100", "6", "2026-09-28", null, "나", null),
				pick("20260928084907621200", "425", "2026-09-28", null, "다", null),
				pick("20260801000000000000", "999", null, null, "라", null, "새분야"),
				pick("20260801000000000001", "1000", null, null, "마", null, null),
				pick("20260101000000000000", "11", "2026-01-29", null, "바", null)));
		given(lookup.byIsbn13(any())).willReturn(List.of());
		given(lookup.byTitleAndAuthor(any())).willReturn(List.of());

		cache.refresh();

		List<PicksCache.Category> categories = cache.snapshot().categories();
		assertThat(categories).extracting(PicksCache.Category::code, PicksCache.Category::name)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("ALL", "전체"),
					org.assertj.core.groups.Tuple.tuple("11", "문학"), org.assertj.core.groups.Tuple.tuple("6", "인문"),
					org.assertj.core.groups.Tuple.tuple("425", "테마"),
					org.assertj.core.groups.Tuple.tuple("999", "새분야"),
					org.assertj.core.groups.Tuple.tuple("1000", "기타"));
		// month, then registration date (unknown last), then recomNo as text, latest first
		assertThat(categories.getFirst().books()).extracting(PicksCache.Book::title)
			.containsExactly("다", "나", "가", "마", "라", "바");
		assertThat(categories.getFirst().books().getFirst().recommendedMonth()).isEqualTo(YearMonth.of(2026, 10));
	}

	@Test
	void aFailedRefreshKeepsTheCopyBefore() { // C9
		given(store.readPicks()).willReturn(List.of(pick("1", "11", null, null, "가", null)));
		given(lookup.byIsbn13(any())).willReturn(List.of());
		given(lookup.byTitleAndAuthor(any())).willReturn(List.of());
		cache.refresh();
		PicksCache.Snapshot before = cache.snapshot();
		given(lookup.byTitleAndAuthor(any())).willThrow(new QueryTimeoutException("lock_timeout"));

		cache.refresh();

		assertThat(cache.snapshot()).isSameAs(before);
	}

	@Test
	void nothingStoredIsEmpty() {
		given(store.readPicks()).willReturn(List.of());

		cache.refresh();

		assertThat(cache.snapshot()).isSameAs(PicksCache.Snapshot.EMPTY);
	}

	private static PicksStore.StoredPick pick(String recomNo, String code, String registered, String isbn, String title,
			String author) {
		return pick(recomNo, code, registered, isbn, title, author, null);
	}

	private static PicksStore.StoredPick pick(String recomNo, String code, String registered, String isbn, String title,
			String author, String codeName) {
		LocalDate month = recomNo.startsWith("202609") ? LocalDate.of(2026, 10, 1)
				: recomNo.startsWith("202608") ? LocalDate.of(2026, 8, 1)
				: recomNo.startsWith("202601") ? LocalDate.of(2026, 2, 1) : LocalDate.of(2026, 10, 1);
		return new PicksStore.StoredPick(recomNo, code, codeName, month, registered == null ? null : LocalDate.parse(registered),
				title, author, isbn, null);
	}

}
