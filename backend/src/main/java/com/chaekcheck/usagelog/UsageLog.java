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
	 * @param elapsedMs from sending to the answer; kept only for {@link ResponseResult#OK} ({@link ResponseResult#CACHED}
	 * sent no request, so its value is ignored)
	 */
	void recordLibraryResponse(long libraryId, ResponseSource source, ResponseResult result, long elapsedMs);

	/**
	 * One AI book search (cycle 24, ADR-0152), at the request's receive time. The row has no question text, IP, visit
	 * key or member number; like the other rows it is kept only when logging is on and the request is not a robot.
	 */
	void recordAiSearch(UsageRequest request, AiSearchRecord record);

	/**
	 * What one AI search did (design decision #7 [확정]).
	 * @param elapsedMs from the request to the end of the answer
	 * @param turnNo the question's number in its conversation, 1 to 20, counted by the web
	 * @param keywordResult the first model call
	 * @param titleLike the first call took the question for a title or an author
	 * @param pickResult how the answer ended
	 * @param candidateCount books given to the second call (0 when it was not called)
	 * @param pickedBookIds the books shown, in order, at most 8
	 * @param inputTokens both calls together
	 * @param outputTokens both calls together
	 */
	record AiSearchRecord(int elapsedMs, int turnNo, KeywordResult keywordResult, boolean titleLike,
			PickResult pickResult, int candidateCount, List<Long> pickedBookIds, int inputTokens, int outputTokens) {

		public AiSearchRecord {
			pickedBookIds = List.copyOf(pickedBookIds);
		}

	}

	enum KeywordResult {

		OK, FAILED, TIMEOUT

	}

	/**
	 * {@code SKIPPED}: the second call was not made for a reason other than no match (for example the user left
	 * before it).
	 */
	enum PickResult {

		OK, NO_MATCH, NO_TEXT, PARTIAL, SKIPPED

	}

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

		/**
		 * {@code CACHED} (cycle 19, ADR-0116): answered from the 3-minute cache, no request sent to the library; counted
		 * only in {@code cached_count}, never in the answer times or the other counts. {@code BODY_TOO_LARGE} (cycle 20,
		 * ADR-0132): the answer passed the body limit (1MB, compressed or decompressed) and the request failed; counted
		 * in {@code failed_count} and in {@code body_too_large_count} both [확정].
		 */
		OK, FAILED, TIMEOUT, SKIPPED, CACHED, BODY_TOO_LARGE

	}

}
