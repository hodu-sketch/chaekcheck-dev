package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.QueryTimeoutException;

import com.chaekcheck.catalog.BookAuthors;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IdBook;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.BookTagTransfer.Tag;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.internal.lists.CoverFiles;
import com.chaekcheck.picks.internal.store.PicksStore;
import com.chaekcheck.picks.internal.store.PicksStore.StoredBook;
import com.chaekcheck.picks.internal.store.PicksStore.StoredField;
import com.chaekcheck.picks.internal.tags.CardTags;

/**
 * The listed books of the AI book search (cycle 24 handoff step 3): rows without our book left out, one row per book
 * over the lists, our book's author, the set kept until the cache is read again, and the import's own read refusing a
 * partial set.
 */
class CacheListedBooksTests {

	final PicksStore store = mock(PicksStore.class);

	final BookLookup lookup = mock(BookLookup.class);

	final LibraryCatalog libraries = mock(LibraryCatalog.class);

	final BookAuthors authors = mock(BookAuthors.class);

	final CardTags cardTags = mock(CardTags.class);

	final PicksCache cache = new PicksCache(store, lookup, libraries);

	final MovableClock clock = new MovableClock();

	final CacheListedBooks listed = new CacheListedBooks(cache, store, lookup, libraries, authors, cardTags, clock);

	@BeforeEach
	void stored() {
		given(libraries.lendingKinds()).willReturn(Map.of());
		given(store.fields()).willReturn(List.of(new StoredField("leedongjin", "science", "과학"),
				new StoredField("snu100", "korean_lit", "한국문학"), new StoredField("kaist", "ai", "AI")));
		given(store.books(any())).willReturn(List.of());
		given(store.books("leedongjin")).willReturn(List.of(row(11, "science", 1, "코스모스", 100L, "c1.jpg"),
				row(12, "science", 2, "목록에만 있는 책", null, null)));
		given(store.books("snu100")).willReturn(List.of(row(21, "korean_lit", 1, "토지", 200L, null)));
		given(store.books("kaist")).willReturn(List.of(row(31, "ai", 1, "코스모스", 100L, "c2.jpg"),
				row(32, "ai", 2, "토지", 200L, "c3.jpg")));
		given(lookup.byIds(any())).willReturn(List.of(new IdBook(100, "코스모스 (개정판)", "", new long[] { 1, 2 }),
				new IdBook(200, "토지", "", new long[] { 3 })));
		given(authors.byIds(any())).willReturn(Map.of(100L, "칼 세이건", 200L, "박경리"));
		given(cardTags.byIds(any())).willReturn(Map.of(100L, List.of(new Tag("m03", "mood", "잔잔한"))));
	}

	/** The first list's field group (user 2026-10-25): 이동진 science, 서울대 한국문학. */
	@Test
	void theFirstListsFieldGivesTheGroup() {
		cache.refresh();

		assertThat(listed.current().books()).extracting(ListedBook::fieldGroup).containsExactly("science", "literature");
	}

	/** Cycle 23 (R9-5): the card tags come with the book, none for a book without tags. */
	@Test
	void theCardTagsComeWithTheBook() {
		cache.refresh();

		assertThat(listed.current().books()).extracting(ListedBook::tags)
			.containsExactly(List.of(new Tag("m03", "mood", "잔잔한")), null);
	}

	@Test
	void oneRowPerOurBookWithEveryListAndOurAuthor() {
		cache.refresh();

		List<ListedBook> books = listed.current().books();

		assertThat(books)
			.extracting(ListedBook::bookId, ListedBook::title, ListedBook::author, ListedBook::coverPath,
					ListedBook::listKeys)
			.containsExactly(tuple(100L, "코스모스", "칼 세이건", CoverFiles.path("c1.jpg"), List.of("leedongjin", "kaist")),
					tuple(200L, "토지", "박경리", CoverFiles.path("c3.jpg"), List.of("snu100", "kaist")));
		assertThat(books.getFirst().libraryIds()).containsExactly(1, 2);
		assertThat(books.getFirst().countHeldBy(Set.of(2L, 9L))).isEqualTo(1);
	}

	@Test
	void theSetIsBuiltAgainOnlyAfterTheCacheIsReadAgain() {
		cache.refresh();

		ListedBooks.Snapshot first = listed.current();
		assertThat(listed.current()).isSameAs(first);
		verify(authors, times(1)).byIds(any());

		cache.refresh();
		assertThat(listed.current()).isNotSameAs(first);
		verify(authors, times(2)).byIds(any());
	}

	@Test
	void afterAFailedAuthorReadTheNewBooksComeWithTheAuthorsKnownBeforeAndTheReadWaitsAMinute() {
		cache.refresh();
		listed.current();
		given(authors.byIds(any())).willThrow(new QueryTimeoutException("slow"));
		given(lookup.byIds(any())).willReturn(List.of(new IdBook(100, "코스모스 (개정판)", "", new long[] { 1, 2, 5 }),
				new IdBook(200, "토지", "", new long[] { 3 })));
		cache.refresh();

		ListedBooks.Snapshot afterFailure = listed.current();
		assertThat(afterFailure.books().getFirst().libraryIds()).containsExactly(1, 2, 5);
		assertThat(afterFailure.books()).extracting(ListedBook::author).containsExactly("칼 세이건", "박경리");
		assertThat(listed.current()).isSameAs(afterFailure);
		verify(authors, times(2)).byIds(any());

		clock.plus(CacheListedBooks.AUTHOR_RETRY);
		listed.current();
		verify(authors, times(3)).byIds(any());
	}

	@Test
	void withoutAnySetTheBooksComeWithoutAuthors() {
		given(authors.byIds(any())).willThrow(new QueryTimeoutException("slow"));
		cache.refresh();

		assertThat(listed.current().books()).extracting(ListedBook::author).containsOnlyNulls();
	}

	@Test
	void emptyUntilTheCacheIsFilled() {
		assertThat(listed.current().books()).isEmpty();
	}

	@Test
	void loadFromDbGivesTheSameBooksAsTheServerCache() {
		cache.refresh();

		ListedBooks.Snapshot fromCache = listed.current();
		ListedBooks.Snapshot fromDb = listed.loadFromDb();

		assertThat(fromDb.books()).extracting(ListedBook::bookId, ListedBook::title, ListedBook::author)
			.containsExactlyElementsOf(fromCache.books()
				.stream()
				.map(book -> tuple(book.bookId(), book.title(), book.author()))
				.toList());
	}

	@Test
	void loadFromDbWorksWithoutTheServerCache() {
		assertThat(listed.loadFromDb().books()).extracting(ListedBook::bookId).containsExactly(100L, 200L);
	}

	@Test
	void loadFromDbRefusesWhenAListWithRowsCannotBeRead() {
		given(lookup.byIds(any())).willThrow(new QueryTimeoutException("catalog is being swapped"));

		assertThatThrownBy(listed::loadFromDb).isInstanceOf(ListedBooks.ListsNotReadException.class)
			.hasMessageContaining("leedongjin")
			.hasMessageContaining("snu100")
			.hasMessageContaining("kaist")
			.hasMessageNotContaining("nobel");
	}

	private static StoredBook row(long id, String field, int position, String title, Long bookId, String cover) {
		return new StoredBook(id, field, position, title, null, null, LocalDate.of(2026, 10, 1), null, null, position,
				null, null, bookId, cover, null);
	}

	/** A clock the test moves forward. */
	static final class MovableClock extends Clock {

		private Instant now = Instant.parse("2026-10-24T00:00:00Z");

		void plus(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
