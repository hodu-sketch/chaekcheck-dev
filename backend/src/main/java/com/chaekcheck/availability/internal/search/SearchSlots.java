package com.chaekcheck.availability.internal.search;

import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Caps the searches that call library sites at the same time: search and holding batch streams and the per-library
 * "more" API ({@code chaekcheck.availability.max-concurrent-searches}). When every slot is taken a new search is
 * refused at once with {@link SearchBusyException} (503 {@code server_busy}) instead of waiting in a queue.
 */
public final class SearchSlots {

	private final int max;

	private final Semaphore permits;

	public SearchSlots(int max) {
		if (max < 1) {
			throw new IllegalArgumentException("max-concurrent-searches must be 1 or more: " + max);
		}
		this.max = max;
		this.permits = new Semaphore(max);
	}

	/**
	 * Takes a slot without waiting.
	 * @throws SearchBusyException when every slot is taken
	 */
	public Slot acquire() {
		if (!permits.tryAcquire()) {
			throw new SearchBusyException(max);
		}
		return new Slot();
	}

	/** Slots free right now (for tests and logs). */
	public int available() {
		return permits.availablePermits();
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
