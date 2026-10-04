package com.chaekcheck.availability.internal.search;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.LibraryType;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.PriorityGroup;
import com.chaekcheck.library.SearchTarget;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Data of the batch stream events (design-delta "API", "이벤트 예시"). Order: {@code targets} (first stream only)
 * -> {@code library-requested} / {@code library-result} / {@code library-failed} ... -> {@code batch-done} ->
 * {@code row-verdict} ... -> {@code done}. {@code library-result} data is a
 * {@link com.chaekcheck.availability.internal.fetch.LibraryPage}.
 */
public final class StreamEvents {

	public static final String TARGETS = "targets";

	public static final String LIBRARY_REQUESTED = "library-requested";

	public static final String LIBRARY_RESULT = "library-result";

	public static final String LIBRARY_FAILED = "library-failed";

	public static final String BATCH_DONE = "batch-done";

	public static final String ROW_VERDICT = "row-verdict";

	public static final String DONE = "done";

	private StreamEvents() {
	}

	/**
	 * The whole search plan in plan order. Disabled libraries, and with scope joinable unjoinable ones, are not in it.
	 * The search text is in the one condition member named like the request parameter ({@code title},
	 * {@code author} or {@code publisher}, ADR-0025); the other two are left out of the JSON. {@code visit} is
	 * {@code any} or {@code none} ("방문 없이 가입": visit-only libraries are not in the plan).
	 */
	public record Targets(@JsonInclude(Include.NON_NULL) String title, @JsonInclude(Include.NON_NULL) String author,
			@JsonInclude(Include.NON_NULL) String publisher, String filter, String scope, String visit,
			boolean regionFilterApplied, List<Library> libraries) {

		public Targets {
			libraries = List.copyOf(libraries);
		}

		public static Targets of(SearchQuery query, String filter, String scope, String visit,
				boolean regionFilterApplied, List<Library> libraries) {
			String value = query.value();
			return switch (query.field()) {
				case TITLE -> new Targets(value, null, null, filter, scope, visit, regionFilterApplied, libraries);
				case AUTHOR -> new Targets(null, value, null, filter, scope, visit, regionFilterApplied, libraries);
				case PUBLISHER -> new Targets(null, null, value, filter, scope, visit, regionFilterApplied, libraries);
			};
		}

	}

	/**
	 * One plan entry, in plan order (design-delta event example). {@code chosen}: the user added it by hand (cycle
	 * 06); the next-batch APIs do not send it again, so the web keeps it from these first {@code targets}.
	 */
	public record Library(long id, String name, LibraryType libraryType, PlatformType platformType,
			PriorityGroup priorityGroup, boolean deferred, Eligibility eligibility, String eligibilitySummary,
			String extraConditionText, boolean visitRequired, String joinMethod, String joinUrl,
			String joinCaution, String joinNotice, String homepageUrl, boolean chosen) {

		public static Library from(SearchTarget target) {
			return new Library(target.id(), target.name(), target.libraryType(), target.platformType(),
					target.priorityGroup(), target.deferred(), target.eligibility(), target.eligibilitySummary(),
					target.extraConditionText(), target.visitRequired(), target.joinMethod(), target.joinUrl(),
					target.joinCaution(), target.joinNotice(), target.homepageUrl(), target.chosen());
		}

	}

	public record LibraryRequested(int batchNo, long libraryId, int page) {
	}

	/** @param problem same shape as an HTTP ProblemDetail body (title, status, detail, code) */
	public record LibraryFailed(int batchNo, long libraryId, int page, Map<String, Object> problem) {
	}

	/**
	 * @param countedRows rows that count toward the row limit (filter all: every decided row; available: AVAILABLE)
	 * @param pendingRows rows still waiting for a row-verdict
	 */
	public record BatchDone(int batchNo, int requested, int succeeded, int failed, int countedRows, int pendingRows,
			StopReason stopReason) {
	}

	public enum StopReason {
		/** more than the row limit counted */
		ROW_LIMIT,
		/** every candidate was requested */
		CANDIDATES_EXHAUSTED,
		/** too little time left to start another request */
		LAUNCH_CUTOFF
	}

	/** A PENDING row's final value. The row is found by libraryId + page + rank. */
	public record RowVerdict(int batchNo, long libraryId, int page, int rank, Availability availability,
			AvailabilityBasis availabilityBasis) {
	}

	public record Done(int batchNo) {
	}

	// Holding stream (cycle 04, ADR-0030): targets -> holding-requested / holding-result / holding-failed ...
	// -> batch-done -> done. Detail pages have no PENDING rows, so there is no row-verdict.

	public static final String HOLDING_REQUESTED = "holding-requested";

	public static final String HOLDING_RESULT = "holding-result";

	public static final String HOLDING_FAILED = "holding-failed";

	/**
	 * The plan and the holdings of the asked books in the plan, holdings in {@code bookIds} order and inside a book in
	 * plan order.
	 */
	public record HoldingTargets(String filter, String scope, String visit, boolean regionFilterApplied,
			List<Library> libraries, List<HoldingEntry> holdings) {

		public HoldingTargets {
			libraries = List.copyOf(libraries);
			holdings = List.copyOf(holdings);
		}

	}

	public record HoldingEntry(long holdingId, long bookId, long libraryId) {
	}

	public record HoldingRequested(int batchNo, long holdingId, long bookId, long libraryId) {
	}

	/**
	 * @param item one row in the same shape as a search page row; {@code rank} is 0 and {@code titleKey} names the
	 * book ({@code book:<id>}) so the web groups rows of one book
	 * @param cached whether the detail page came from the 3-minute cache
	 */
	public record HoldingResult(int batchNo, long holdingId, long bookId, long libraryId, LibraryPage.Item item,
			Instant fetchedAt, boolean cached) {
	}

	/** @param problem same shape as an HTTP ProblemDetail body (title, status, detail, code) */
	public record HoldingFailed(int batchNo, long holdingId, long bookId, long libraryId, Map<String, Object> problem) {
	}

}
