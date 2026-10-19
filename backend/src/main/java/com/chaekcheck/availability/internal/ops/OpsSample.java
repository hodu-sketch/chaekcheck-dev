package com.chaekcheck.availability.internal.ops;

import java.time.Instant;

/**
 * One row of {@code availability.lookup_sample} (user decision 2026-10-19): the numbers of the {@code lookup ops:}
 * summary line for one 10-second window. Counts and {@code *MaxInUse} are for the window that ended at
 * {@code sampledAt} ({@code windowMs} long); the rest is the state at {@code sampledAt}.
 * @param heapMaxMb null when the JVM reports no heap limit
 */
public record OpsSample(Instant sampledAt, int windowMs, int slotsMax, int slotsInUse, int slotsMaxInUse,
		int refused, int libraryRequests, int bodyTooLarge, int dbErrors, int parseMax, int parseInUse,
		int parseMaxInUse, int queuedLibraries, int pausedLanes, int heapUsedMb, Integer heapMaxMb, String egressMode,
		int laneRequests, int lanePauses, int laneRetests, int laneResumes) {
}
