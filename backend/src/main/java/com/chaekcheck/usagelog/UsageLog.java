package com.chaekcheck.usagelog;

import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Records searches, book pages, "more" requests and library answers (cycle 15, ADR-0097). Every method returns at once
 * and never throws: the rows are queued and written later by the module. Callers do not know whether logging is on.
 */
public interface UsageLog {

	/**
	 * Takes what the log needs from the request on the request thread (receive time, IP from {@code X-Real-IP}, robot
	 * check, login status), so a record made later on another thread does not read the request. When logging is off,
	 * or the request comes from a robot, the result records nothing.
	 */
	UsageRequest capture(HttpServletRequest request);

	/** A search: a catalog search's first page, or a direct search when its first batch is done. */
	void recordSearch(UsageRequest request, SearchKind kind, SearchConditions conditions, Integer resultCount);

	/** A book page that was answered. */
	void recordBookView(UsageRequest request, long bookId, List<Long> residenceRegionIds, List<Long> workRegionIds,
			List<Long> libraryIds);

	/**
	 * "More" after a search.
	 * @param seq batch number ({@link MoreKind#DIRECT_NEXT_BATCH}) or page
	 * @param libraryId only for {@link MoreKind#LIBRARY_MORE}, otherwise null
	 */
	void recordMore(UsageRequest request, MoreKind kind, int seq, Long libraryId, SearchConditions conditions);

	/**
	 * One request to a library site, counted per Seoul day of its end. Not tied to who searched, so robots count too.
	 * @param elapsedMs from sending to the answer; kept only for {@link ResponseResult#OK}
	 */
	void recordLibraryResponse(long libraryId, ResponseSource source, ResponseResult result, long elapsedMs);

	enum SearchKind {

		CATALOG, DIRECT

	}

	enum MoreKind {

		DIRECT_NEXT_BATCH, LIBRARY_MORE, CATALOG_NEXT_PAGE

	}

	enum ResponseSource {

		DIRECT_SEARCH, HOLDING_FILL

	}

	enum ResponseResult {

		OK, FAILED, TIMEOUT, SKIPPED

	}

}
