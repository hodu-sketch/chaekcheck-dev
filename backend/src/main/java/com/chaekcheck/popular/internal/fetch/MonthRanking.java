package com.chaekcheck.popular.internal.fetch;

import java.time.YearMonth;
import java.util.List;

/**
 * A month fetched in full: 15 lists of 50 books, in request order ({@code PopularCategory}).
 * @param requestCount requests sent to the site for it (session, ranking and sales; AC-12)
 */
public record MonthRanking(YearMonth month, List<CategoryRanking> categories, int requestCount) {

	public MonthRanking {
		categories = List.copyOf(categories);
	}

	public int bookCount() {
		return categories.stream().mapToInt(category -> category.books().size()).sum();
	}

	public int missingIsbnCount() {
		return categories.stream().mapToInt(category -> RankPageParser.missingIsbnCount(category.books())).sum();
	}

}
