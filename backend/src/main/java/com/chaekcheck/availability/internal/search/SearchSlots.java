package com.chaekcheck.availability.internal.search;

import java.time.Clock;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;
import com.chaekcheck.availability.internal.state.RefusalRecord;

/**
 * Caps the searches that call library sites at the same time: search and holding batch streams and the per-library
 * "more" API ({@code chaekcheck.availability.max-concurrent-searches}). When every slot is taken a new search is
 * refused at once with {@link SearchBusyException} (503 {@code server_busy}) instead of waiting in a queue.
 * <p>
 * Cycle 20 (ADR-0118): a refusal writes its time into the {@link RefusalRecord} store (the status signal is RED
 * for 10 seconds after it) and counts for the operations summary; {@link #inUse()} feeds the YELLOW / RED levels.
 */
public final class SearchSlots {

	private final int max;

	private final Semaphore permits;

	private final RefusalRecord refusals;

	private final LookupOpsCounters counters;

	private final Clock clock;

	/** Memory record, fresh counters and the system clock: for tests and the "more" path's test configuration. */
	public SearchSlots(int max) {
		this(max, new InMemoryLastTimeRecord(), new LookupOpsCounters(), Clock.systemUTC());
	}

	public SearchSlots(int max, RefusalRecord refusals, LookupOpsCounters counters, Clock clock) {
		if (max < 1) {
			throw new IllegalArgumentException("max-concurrent-searches must be 1 or more: " + max);
		}
		this.max = max;
		this.permits = new Semaphore(max);
		this.refusals = refusals;
		this.counters = counters;
		this.clock = clock;
	}

	/**
	 * Takes a slot without waiting.
	 * @throws SearchBusyException when every slot is taken
	 */
	public Slot acquire() {
		if (!permits.tryAcquire()) {
			refusals.mark(clock.instant());
			counters.refused();
			throw new SearchBusyException(max);
		}
		counters.slotsInUse(inUse());
		return new Slot();
	}

	/** Slots free right now (for tests and logs). */
	public int available() {
		return permits.availablePermits();
	}

	/** Slots taken right now ({@code max - available}), for the status signal. */
	public int inUse() {
		return max - permits.availablePermits();
	}

	public int max() {
		return max;
	}

	/** One taken slot. Releasing it again does nothing, so a timer and the normal end may both release it. */
	public final class Slot implements AutoCloseable {

		private final AtomicBoolean released = new AtomicBoolean();

		private Slot() {
		}

		@Override
		public void close() {
			if (released.compareAndSet(false, true)) {
				permits.release();
			}
		}

	}

}
