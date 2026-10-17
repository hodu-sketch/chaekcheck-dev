package com.chaekcheck.availability.internal.ops;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the one-minute operations summary line (cycle 20, ADR-0132, overall review #5): library requests the
 * server sent, answers over the body limit, searches refused with 503, DB errors seen by the API, and the most search
 * slots in use. {@link #drain()} hands the counts to the summary log and starts the next minute at zero. Per process
 * on purpose: the log line describes this server, so these are not shared state behind the store interface
 * (ADR-0133 lists what is).
 */
public final class LookupOpsCounters {

	private final AtomicLong libraryRequests = new AtomicLong();

	private final AtomicLong bodyTooLarge = new AtomicLong();

	private final AtomicLong refusals = new AtomicLong();

	private final AtomicLong dbErrors = new AtomicLong();

	private final AtomicInteger maxSlotsInUse = new AtomicInteger();

	/** One request sent to a library site by this server (not an answer from the cache). */
	public void libraryRequest() {
		libraryRequests.incrementAndGet();
	}

	/** One answer over the body limit, compressed or decompressed. */
	public void bodyTooLarge() {
		bodyTooLarge.incrementAndGet();
	}

	/** One search refused with 503 {@code server_busy}. */
	public void refused() {
		refusals.incrementAndGet();
	}

	/** One DB error seen by the root exception handler. */
	public void dbError() {
		dbErrors.incrementAndGet();
	}

	/** Search slots in use right after one was taken; the summary keeps the minute's maximum. */
	public void slotsInUse(int inUse) {
		maxSlotsInUse.accumulateAndGet(inUse, Math::max);
	}

	/** The counts since the last drain; the counters start again at zero. */
	public Snapshot drain() {
		return new Snapshot(libraryRequests.getAndSet(0), bodyTooLarge.getAndSet(0), refusals.getAndSet(0),
				dbErrors.getAndSet(0), maxSlotsInUse.getAndSet(0));
	}

	public record Snapshot(long libraryRequests, long bodyTooLarge, long refusals, long dbErrors, int maxSlotsInUse) {
	}

}
