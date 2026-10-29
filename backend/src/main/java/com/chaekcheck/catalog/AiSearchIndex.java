package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Public API of the catalog module for the AI book search (cycle 26, ADR-0165): the keyword match over
 * {@code catalog.book_search} (the queries of the aisearch module's {@code CandidateQuery}, only the table changed, F6)
 * and the books it answers with. The plan library count uses the table's own {@code library_ids} (ADR-0160).
 */
public interface AiSearchIndex {

	/**
	 * @param bookId a matched book
	 * @param score how many words it holds
	 * @param planCount how many plan libraries hold it
	 */
	record Candidate(long bookId, int score, int planCount) {
	}

	/**
	 * @param title {@code catalog.book.title}
	 * @param author {@code catalog.book.author_display}, or null
	 * @param publishedYear the year of {@code catalog.book.published_on}, or null (user 2026-10-29: 「최신」 filters the
	 * vector candidates by it)
	 */
	record SearchBook(long bookId, String title, String author, Integer publishedYear) {

		public SearchBook(long bookId, String title, String author) {
			this(bookId, title, author, null);
		}

	}

	/**
	 * The search books held by at least one plan library and holding at least one word, best first: core words found,
	 * words found, plan libraries holding the book, then book id; cut after the order, at most {@code limit}. Excluded
	 * books are left out. No word, no plan library or a limit below 1 gives an empty list without a query.
	 * @param words the words to match, already tidied ({@link SearchText#tidy}), each once
	 * @param coreWords those of {@code words} that count first
	 */
	default List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries, Set<Long> excluded,
			int limit) {
		return find(words, coreWords, planLibraries, excluded, limit, null);
	}

	/**
	 * As {@link #find(List, Set, Set, Set, int)}, among books holding as many words those published in or after
	 * {@code fromYear} first when it is given (「최신」, user 2026-10-29).
	 */
	List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries, Set<Long> excluded,
			int limit, Integer fromYear);

	/** How many plan libraries hold each given search book; books held by none (or not stored) are left out. */
	Map<Long, Integer> planCounts(Collection<Long> bookIds, Set<Long> planLibraries);

	/** The search lines of the given books; books without one are left out. */
	Map<Long, String> lines(Collection<Long> bookIds);

	/** Every search book with its title and author, in book id order. */
	List<SearchBook> books();

	/** What the table holds now (row count and newest import time), to read {@link #books()} again only on change. */
	String version();

	/**
	 * Rewrites the table without the space replaced rows left ({@code VACUUM FULL}). Outside any transaction; it locks
	 * the table while it runs, so the AI search waits that long.
	 */
	void compact();

}
