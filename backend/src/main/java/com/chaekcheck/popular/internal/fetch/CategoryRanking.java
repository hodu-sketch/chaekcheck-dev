package com.chaekcheck.popular.internal.fetch;

import java.util.List;

import com.chaekcheck.popular.internal.PopularCategory;

/**
 * One list of a month.
 * @param salesCopies the category's sales count; null for "전체"
 * @param displayOrder 0 for "전체", 1 to 14 by sales count ({@link CategoryOrder})
 * @param books 50 books in rank order
 */
public record CategoryRanking(PopularCategory category, Long salesCopies, int displayOrder, List<RankedBook> books) {

	public CategoryRanking {
		books = List.copyOf(books);
	}

}
