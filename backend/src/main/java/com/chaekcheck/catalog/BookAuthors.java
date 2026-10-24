package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.Map;

/**
 * Public API of the catalog module for the AI book search (cycle 24): the author text our books show
 * ({@code catalog.book.author_display}, the same text as the book page and the search results), read by book id. The
 * picks module adds it to its listed books, whose cache keeps no author (user 2026-10-24: "우리 쪽 db랑 싱크가 맞춰야
 * 하니까").
 * <p>
 * TODO(24-F1): when the picks cache or the book matching is changed (cycle 23 merge included), let the cache carry this
 * author itself and drop this extra read (dev docs/plan-feedback.md "후속 작업 24-F1", docs/maintenance/4-picks.md 10절).
 */
public interface BookAuthors {

	/**
	 * The author text of the given books, by book id. Books without one (no author text, or no such book) are left
	 * out; nulls are skipped. An empty input gives an empty map. One query for any number of ids.
	 */
	Map<Long, String> byIds(Collection<Long> bookIds);

}
