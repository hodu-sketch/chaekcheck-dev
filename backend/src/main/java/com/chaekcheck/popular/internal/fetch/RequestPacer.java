package com.chaekcheck.popular.internal.fetch;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Keeps at least {@code interval} between the end of one request to the site and the start of the next (plan cycle
 * 10 C4, counted from the end [안]). One pacer per fetch; every request (session, ranking, sales, cover) goes through
 * it, one at a time. The gaps are kept for the log and for tests (AC-12).
 */
public final class RequestPacer {

	private final Duration interval;

	private final Ticker ticker;

	private final List<Duration> gaps = new ArrayList<>();

	private long lastEnd;

	private boolean started;

	public RequestPacer(Duration interval, Ticker ticker) {
		this.interval = interval;
		this.ticker = ticker;
	}

	/**
	 * Waits until the interval has passed since the last request ended.
	 * @return the gap since the last request ended, or null for the first request
	 */
	public Duration awaitTurn() throws InterruptedException {
		if (!started) {
			return null;
		}
		long left = interval.toNanos() - (ticker.nanoTime() - lastEnd);
		if (left > 0) {
			ticker.sleep(Duration.ofNanos(left));
		}
		Duration gap = Duration.ofNanos(ticker.nanoTime() - lastEnd);
		gaps.add(gap);
		return gap;
	}

	/** The request has ended (answer read or failed). */
	public void finished() {
		lastEnd = ticker.nanoTime();
		started = true;
	}

	/** Gaps between requests so far, in order. */
	public List<Duration> gaps() {
		return List.copyOf(gaps);
	}

	long nanoTime() {
		return ticker.nanoTime();
	}

	/** Time source and sleep; tests use a fake that does not sleep. */
	public interface Ticker {

		Ticker SYSTEM = new Ticker() {

			@Override
			public long nanoTime() {
				return System.nanoTime();
			}

			@Override
			public void sleep(Duration duration) throws InterruptedException {
				Thread.sleep(duration);
			}

		};

		long nanoTime();

		void sleep(Duration duration) throws InterruptedException;

	}

}
