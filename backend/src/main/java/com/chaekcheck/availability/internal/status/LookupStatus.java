package com.chaekcheck.availability.internal.status;

import java.time.Instant;
import java.util.List;

/**
 * The answer of {@code GET /api/v1/lookup-status} (cycle 20, ADR-0118, design-delta "API").
 * @param slots search slots in all ({@code max-concurrent-searches})
 * @param inUse slots taken when the answer was computed
 * @param level the judged level; the web never recomputes it from the numbers
 * @param reason why, when the level needs a second line: {@code DB_ERROR} with GRAY, {@code NO_LANE} with RED,
 * {@code VENDOR_PAUSED} with YELLOW, else {@code NONE}
 * @param queuedLibraryIds libraries waiting behind a busy host (spec R4), at most {@code max-queued-libraries}
 * @param serverTime when the answer was computed
 */
public record LookupStatus(int slots, int inUse, Level level, Reason reason, List<Long> queuedLibraryIds,
		Instant serverTime) {

	public LookupStatus {
		queuedLibraryIds = List.copyOf(queuedLibraryIds);
	}

	public enum Level {

		GREEN, YELLOW, RED, GRAY

	}

	public enum Reason {

		NONE, VENDOR_PAUSED, NO_LANE, DB_ERROR

	}

}
