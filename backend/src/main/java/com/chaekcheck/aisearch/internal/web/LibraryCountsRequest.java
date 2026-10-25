package com.chaekcheck.aisearch.internal.web;

import java.util.List;

/**
 * The body of {@code POST /api/v1/ai-searches/library-counts}: the books the conversation showed and the plan values,
 * as in {@link AiSearchRequest}. Unknown members are ignored.
 */
record LibraryCountsRequest(List<Long> bookIds, List<Long> residenceRegionIds, List<Long> workRegionIds,
		List<Long> libraryIds, String scope, String visit) {
}
