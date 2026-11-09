package com.chaekcheck.aisearch.internal.match;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.chaekcheck.catalog.AiSearchIndex;

/** Cycle 29 (ADR-0174): the books are read once, then only on reload; a failed reload keeps the books in use. */
class SearchBooksReadTests {

	@Test
	void theBooksAreReadOnceAndAgainOnlyOnReload() {
		CountingIndex index = new CountingIndex();
		SearchBooks books = new SearchBooks(index);

		assertThat(books.books()).hasSize(1);
		assertThat(books.byId()).containsKey(1L);
		assertThat(index.reads).isOne();

		index.next = List.of(new AiSearchIndex.SearchBook(1, "가", null), new AiSearchIndex.SearchBook(2, "나", null));
		assertThat(books.books()).hasSize(1);
		assertThat(books.reload()).isEqualTo(2);
		assertThat(books.books()).hasSize(2);
	}

	@Test
	void aFailedReloadKeepsTheBooksInUse() {
		CountingIndex index = new CountingIndex();
		SearchBooks books = new SearchBooks(index);
		books.reload();

		index.fail = true;
		assertThatThrownBy(books::reload).isInstanceOf(IllegalStateException.class);
		assertThat(books.books()).hasSize(1);
	}

	@Test
	void aFailedFirstReadAnswersWithNoBooksAndTriesAgain() {
		CountingIndex index = new CountingIndex();
		index.fail = true;
		SearchBooks books = new SearchBooks(index);

		assertThat(books.books()).isEmpty();
		index.fail = false;
		assertThat(books.books()).hasSize(1);
	}

	private static final class CountingIndex implements AiSearchIndex {

		List<AiSearchIndex.SearchBook> next = List.of(new AiSearchIndex.SearchBook(1, "가", null));

		int reads;

		boolean fail;

		@Override
		public List<AiSearchIndex.SearchBook> books() {
			if (fail) {
				throw new IllegalStateException("down");
			}
			reads++;
			return new ArrayList<>(next);
		}

		@Override
		public List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries,
				Set<Long> excluded, int limit, Integer fromYear) {
			throw new AssertionError();
		}

		@Override
		public Map<Long, Integer> planCounts(Collection<Long> bookIds, Set<Long> planLibraries) {
			throw new AssertionError();
		}

		@Override
		public Map<Long, String> lines(Collection<Long> bookIds) {
			throw new AssertionError();
		}

	}

}
