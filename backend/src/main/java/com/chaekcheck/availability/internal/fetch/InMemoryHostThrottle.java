package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * {@link HostThrottle} kept in this process's memory. Correct only while the main service runs as one instance.
 */
public class InMemoryHostThrottle implements HostThrottle {

	private static final Logger log = LoggerFactory.getLogger(InMemoryHostThrottle.class);

	private final int maxConcurrency;

	private final Duration minInterval;

	private final Clock clock;

	private final Map<String, Host> hosts = new ConcurrentHashMap<>();

	public InMemoryHostThrottle(int maxConcurrency, Duration minInterval, Clock clock) {
		this.maxConcurrency = maxConcurrency;
		this.minInterval = minInterval;
		this.clock = clock;
	}

	@Override
	public <T> T run(URI uri, Instant deadline, Callable<T> call) {
		String key = HostThrottle.key(uri);
		Host host = hosts.computeIfAbsent(key, k -> new Host(new Semaphore(maxConcurrency, true)));
		acquire(host, uri, deadline);
		boolean called = false;
		try {
			Instant now = clock.instant();
			Instant earliest = host.lastEnd == null ? now : host.lastEnd.plus(minInterval);
			if (earliest.isAfter(deadline)) {
				throw UpstreamException.timeout("throttle wait for " + HostThrottle.hostPort(uri) + " passes the deadline");
			}
			sleepUntil(earliest);
			called = true;
			return call.call();
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new UpstreamException(UpstreamException.Kind.ERROR, "call to " + HostThrottle.hostPort(uri) + " failed",
					ex);
		}
		finally {
			if (called) {
				host.lastEnd = clock.instant();
			}
			host.permits.release();
		}
	}

	private void acquire(Host host, URI uri, Instant deadline) {
		long waitNanos = Duration.between(clock.instant(), deadline).toNanos();
		try {
			if (waitNanos <= 0 || !host.permits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) {
				log.info("throttle timeout host={} key={} (waiting for a free slot)", HostThrottle.hostPort(uri),
						HostThrottle.key(uri));
				throw UpstreamException.timeout("no free slot for " + HostThrottle.hostPort(uri) + " before the deadline");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UpstreamException(UpstreamException.Kind.ERROR,
					"interrupted while waiting for " + HostThrottle.hostPort(uri), ex);
		}
	}

	private void sleepUntil(Instant earliest) throws InterruptedException {
		Duration wait = Duration.between(clock.instant(), earliest);
		if (!wait.isNegative() && !wait.isZero()) {
			Thread.sleep(wait);
		}
	}

	private static final class Host {

		final Semaphore permits;

		volatile Instant lastEnd;

		Host(Semaphore permits) {
			this.permits = permits;
		}

	}

}
