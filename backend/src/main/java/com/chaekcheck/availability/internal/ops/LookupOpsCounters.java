package com.chaekcheck.availability.internal.ops;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Counters for the one-minute operations summary line (cycle 20, ADR-0132, overall review #5): library requests the
 * server sent, answers over the body limit, searches refused with 503, DB errors seen by the API, and the most search
 * slots in use. {@link #drain()} hands the counts to the summary log and starts the next minute at zero. Per process
 * on purpose: the log line describes this server, so these are not shared state behind the store interface
 * (ADR-0133 lists what is).
 * <p>
 * The 10-second DB sample (user decision 2026-10-19, {@link OpsSampler}) has its own window of the same counts,
 * {@link #drainSample()}, so taking a sample never empties the minute's counts and the other way round.
 */
public final class LookupOpsCounters {

	private final Window minute = new Window();

	private final Window sample = new Window();

	/** One request sent to a library site by this server (not an answer from the cache). */
	public void libraryRequest() {
		minute.libraryRequests.incrementAndGet();
		sample.libraryRequests.incrementAndGet();
	}

	/** One answer over the body limit, compressed or decompressed. */
	public void bodyTooLarge() {
		minute.bodyTooLarge.incrementAndGet();
		sample.bodyTooLarge.incrementAndGet();
	}

	/** One search refused with 503 {@code server_busy}. */
	public void refused() {
		minute.refusals.incrementAndGet();
		sample.refusals.incrementAndGet();
	}

	/** One DB error seen by the root exception handler. */
	public void dbError() {
		minute.dbErrors.incrementAndGet();
		sample.dbErrors.incrementAndGet();
	}

	/** Search slots in use right after one was taken; the summary keeps the minute's maximum. */
	public void slotsInUse(int inUse) {
		minute.maxSlotsInUse.accumulateAndGet(inUse, Math::max);
		sample.maxSlotsInUse.accumulateAndGet(inUse, Math::max);
	}

	/** The counts since the last drain; the counters start again at zero. */
	public Snapshot drain() {
		return minute.drain();
	}

	/** The counts since the last sample (the 10-second DB row); the sample's counters start again at zero. */
	public Snapshot drainSample() {
		return sample.drain();
	}

	public record Snapshot(long libraryRequests, long bodyTooLarge, long refusals, long dbErrors, int maxSlotsInUse) {
	}

	private static final class Window {

		final AtomicLong libraryRequests = new AtomicLong();

		final AtomicLong bodyTooLarge = new AtomicLong();

		final AtomicLong refusals = new AtomicLong();

		final AtomicLong dbErrors = new AtomicLong();

		final AtomicInteger maxSlotsInUse = new AtomicInteger();

		Snapshot drain() {
			return new Snapshot(libraryRequests.getAndSet(0), bodyTooLarge.getAndSet(0), refusals.getAndSet(0),
					dbErrors.getAndSet(0), maxSlotsInUse.getAndSet(0));
		}

	}

}
