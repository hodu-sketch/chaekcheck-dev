package com.chaekcheck.aisearch.internal.web;

import java.util.List;

/**
 * The stream request body (design-delta "API" [안]). Read with the application's JSON mapper; unknown members are
 * ignored. Checked in {@link AiSearchController}, never logged.
 */
record AiSearchRequest(String question, Integer turnNo, List<HistoryTurn> history, List<Long> residenceRegionIds,
		List<Long> workRegionIds, List<Long> libraryIds, String scope, String visit) {

	/**
	 * @param askQuestion the ask-back that turn showed instead of books, or null (user 2026-10-27)
	 * @param askChoices its choices
	 */
	record HistoryTurn(String question, List<Long> bookIds, String askQuestion, List<String> askChoices) {
	}

}
