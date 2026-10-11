package com.chaekcheck.popular.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.popular.internal.store.PopularStore;

/** The shared cache (ADR-0074, plan cycle 10 C8). */
class PopularCacheTests {

	static final Instant FETCHED = Instant.parse("2026-10-02T20:01:32Z");

	final PopularStore store = mock(PopularStore.class);

	final BookLookup bookLookup = mock(BookLookup.class);

	final PopularCache cache = new PopularCache(store, bookLookup);

	@Test
	void emptyUntilAMonthIsStored() {
		given(store.readLatestMonth()).willReturn(Optional.empty());

		cache.refresh();

		assertThat(cache.snapshot()).isSameAs(PopularCache.Snapshot.EMPTY);
	}

	@Test
	void booksGetOurBookByIsbnAndListsTheirShortNames() {
		given(store.readLatestMonth()).willReturn(Optional.of(month("가", "9799980000001")));
		given(bookLookup.byIsbn13(List.of("9799980000001")))
			.willReturn(List.of(new IsbnBook("9799980000001", 7, new long[] { 3, 4 })));

		cache.refresh();

		PopularCache.Snapshot snapshot = cache.snapshot();
		assertThat(snapshot.month()).isEqualTo(YearMonth.of(2026, 9));
		assertThat(snapshot.categories()).extracting(PopularCache.Category::name).containsExactly("전체", "소설");
		PopularCache.Book first = snapshot.categories().getFirst().books().getFirst();
		assertThat(first.bookId()).isEqualTo(7L);
		assertThat(first.libraryIds()).containsExactly(3, 4);
		assertThat(first.coverId()).isEqualTo(11L);
		PopularCache.Book noIsbn = snapshot.categories().get(1).books().getFirst();
		assertThat(noIsbn.bookId()).isNull();
		assertThat(noIsbn.libraryIds()).isEmpty();
	}

	@Test
	void aFailedRefreshKeepsTheCopyBefore() {
		given(store.readLatestMonth()).willReturn(Optional.of(month("가", "9799980000001")));
		given(bookLookup.byIsbn13(any())).willReturn(List.of());
		cache.refresh();
		PopularCache.Snapshot before = cache.snapshot();

		given(store.readLatestMonth()).willReturn(Optional.of(month("바뀐 제목", "9799980000001")));
		given(bookLookup.byIsbn13(any())).willThrow(new IllegalStateException("catalog swap lock_timeout"));
		cache.refresh();

		assertThat(cache.snapshot()).isSameAs(before);
		assertThat(cache.snapshot().categories().getFirst().books().getFirst().title()).isEqualTo("가");
	}

	private static PopularStore.StoredMonth month(String title, String isbn) {
		return new PopularStore.StoredMonth(YearMonth.of(2026, 9), FETCHED,
				List.of(new PopularStore.StoredCategory("ALL", List.of(new PopularStore.StoredBook(1, title, isbn, 11L))),
						new PopularStore.StoredCategory("00000000000000000303",
								List.of(new PopularStore.StoredBook(1, "나", null, null))),
						new PopularStore.StoredCategory("00000000000000002725",
								List.of(new PopularStore.StoredBook(1, "만화", null, null)))));
	}

}
