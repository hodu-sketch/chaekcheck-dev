package com.chaekcheck.aisearch.internal.vector;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Stops asking a failing question encoder for a while (user 2026-10-25, "임베딩 서버 장애 차단"): after
 * {@code failuresToOpen} failures in a row it opens, and for {@code openFor} every lookup is skipped at once, so a dead
 * embedding server costs no search its wait. Then one lookup is let through (half open): a success closes it, a failure
 * opens it again. Thread-safe; transitions are rare, so it simply synchronises.
 */
final class CircuitBreaker {

	enum State {
		CLOSED, OPEN, HALF_OPEN
	}

	/** What a call to {@link #failure()} or {@link #success()} changed, for one log line per change. */
	enum Change {
		NONE, OPENED, CLOSED
	}

	private final int failuresToOpen;

	private final long openForNanos;

	private final LongSupplier nanoTime;

	private final AtomicBoolean trialOut = new AtomicBoolean();

	private State state = State.CLOSED;

	private int failures;

	private long openedAt;

	private long skipped;

	CircuitBreaker(int failuresToOpen, Duration openFor, LongSupplier nanoTime) {
		if (failuresToOpen < 1) {
			throw new IllegalArgumentException("failures to open must be 1 or more: " + failuresToOpen);
		}
		this.failuresToOpen = failuresToOpen;
		this.openForNanos = openFor.toNanos();
		this.nanoTime = nanoTime;
	}

	/** Whether this lookup may go to the encoder; false counts it as skipped. */
	synchronized boolean allow() {
		if (state == State.OPEN && nanoTime.getAsLong() - openedAt >= openForNanos) {
			state = State.HALF_OPEN;
			trialOut.set(false);
		}
		if (state == State.CLOSED) {
			return true;
		}
		if (state == State.HALF_OPEN && trialOut.compareAndSet(false, true)) {
			return true;
		}
		skipped++;
		return false;
	}

	synchronized Change success() {
		failures = 0;
		if (state != State.CLOSED) {
			state = State.CLOSED;
			return Change.CLOSED;
		}
		return Change.NONE;
	}

	synchronized Change failure() {
		failures++;
		if (state == State.HALF_OPEN || (state == State.CLOSED && failures >= failuresToOpen)) {
			state = State.OPEN;
			openedAt = nanoTime.getAsLong();
			return Change.OPENED;
		}
		return Change.NONE;
	}

	synchronized State state() {
		return state;
	}

	/** Lookups skipped since the last call, then zero. */
	synchronized long takeSkipped() {
		long count = skipped;
		skipped = 0;
		return count;
	}

}
