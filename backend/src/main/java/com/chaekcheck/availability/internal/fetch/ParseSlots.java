package com.chaekcheck.availability.internal.fetch;

import java.time.Duration;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Caps the library pages being parsed at the same time (cycle 20, overall review #4, AC-33): parsing a 1MB page was
 * measured at about 7.4MB of heap, and with 40 search slots x 5 libraries up to 200 pages could be parsed at once.
 * A page waits for a slot after its body has arrived, at most {@code wait} (2s); then that library alone fails with
 * {@code parse_busy} (ERROR: {@code library-failed}, usage log FAILED, nothing cached). Search pages, detail pages
 * and, from stage 2, the extension uploads all share this one instance.
 * <p>
 * Per process on purpose: it protects this server's heap, so it is not shared state behind the store interface
 * (ADR-0133). Settings: {@code chaekcheck.availability.parse-slots.max} / {@code .max-wait}
 * (docs/maintenance/settings-index.md).
 */
public final class ParseSlots {

	/** Problem code of a page that got no parse slot in time [안]. */
	public static final String PARSE_BUSY = "parse_busy";

	private final int max;

	private final Semaphore permits;

	private final Duration wait;

	private final AtomicInteger inUse = new AtomicInteger();

	private final AtomicInteger maxInUseSeen = new AtomicInteger();

	private final AtomicInteger sampleMaxInUseSeen = new AtomicInteger();

	public ParseSlots(int max, Duration wait) {
		if (max < 1) {
			throw new IllegalArgumentException("parse-slots.max must be 1 or more: " + max);
		}
		this.max = max;
		this.permits = new Semaphore(max, true);
		this.wait = wait;
	}

	/**
	 * Runs {@code parse} inside a slot.
	 * @throws UpstreamException ERROR with code {@link #PARSE_BUSY} when no slot is free within the wait; whatever
	 * {@code parse} throws otherwise
	 */
	public <T> T parse(Supplier<T> parse) {
		boolean taken;
		try {
			taken = permits.tryAcquire(wait.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UpstreamException(UpstreamException.Kind.ERROR, "interrupted while waiting for a parse slot", ex);
		}
		if (!taken) {
			throw UpstreamException.parseBusy("no parse slot free within " + wait.toMillis() + "ms (" + max + " slots)");
		}
		try {
			int now = inUse.incrementAndGet();
			maxInUseSeen.accumulateAndGet(now, Math::max);
			sampleMaxInUseSeen.accumulateAndGet(now, Math::max);
			return parse.get();
		}
		finally {
			inUse.decrementAndGet();
			permits.release();
		}
	}

	public int max() {
		return max;
	}

	/** Pages being parsed right now. */
	public int inUse() {
		return inUse.get();
	}

	/** The most pages parsed at once since the last call; starts again at zero (for the operations summary). */
	public int drainMaxInUse() {
		return maxInUseSeen.getAndSet(0);
	}

	/** As {@link #drainMaxInUse()}, but for the 10-second DB sample; the two maxima are kept apart. */
	public int drainSampleMaxInUse() {
		return sampleMaxInUseSeen.getAndSet(0);
	}

}
