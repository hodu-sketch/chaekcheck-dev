package com.chaekcheck.availability.internal.fetch;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

/**
 * Which libraries are queued behind a busy host right now (cycle 20, ADR-0118, spec R4, overall review #3): the
 * status signal lists them as {@code queuedLibraryIds} and the web shows 「지금 이 도서관 조회가 밀려요」.
 * <ul>
 * <li>A wait is the time between asking the {@link HostThrottle} for a host's slot and the call starting. The two
 * callers ({@code LibraryPageFetcher}, {@code DetailPageFetcher}) wrap the throttle: {@link #begin} before
 * {@code throttle.run}, {@link Wait#end()} as the first line of the call and again in {@code finally} (ending twice
 * is one end).</li>
 * <li>A wait counts only while it is younger than the wait window (10s = batch deadline 8s + 2s), so a lost end
 * heals itself; a throttle timeout keeps the host queued for the timeout window (60s).</li>
 * <li>Keys are {@code (laneId, hostKey, libraryId)}: several libraries on one host (a portal) are all queued when
 * that host is. Until cycle 17 brings lanes, the lane is {@link #DEFAULT_LANE} and blocked lanes are none.</li>
 * </ul>
 * Behind a store interface (ADR-0133): {@link InMemoryHostQueueBoard} is the {@code memory} implementation.
 */
public interface HostQueueBoard {

	/** The one lane before cycle 17/22 add egress lanes [안]. */
	String DEFAULT_LANE = "direct";

	/** A wait for {@code hostKey}'s slot on {@code laneId} starts now for {@code libraryId}. */
	Wait begin(String laneId, String hostKey, long libraryId, Instant now);

	/** The throttle gave up on {@code hostKey} before the deadline (TIMEOUT while waiting). */
	void timedOut(String laneId, String hostKey, Instant now);

	/**
	 * Libraries queued at {@code now}: with a wait begun within {@code waitWindow}, or on a host with a timeout within
	 * {@code timeoutWindow} (every library seen waiting on that host since the start). Within one host in ascending
	 * library id; at most {@code limit}.
	 */
	Set<Long> queuedLibraryIds(Instant now, Duration waitWindow, Duration timeoutWindow, int limit);

	/** Waits counted right now (younger than the window), for logs and tests. */
	int waitingCount(Instant now, Duration waitWindow);

	/** One wait. {@link #end()} may be called more than once; only the first one counts. */
	interface Wait extends AutoCloseable {

		void end();

		/** Whether {@link #end()} was called (the call started). */
		boolean ended();

		@Override
		default void close() {
			end();
		}

	}

}
