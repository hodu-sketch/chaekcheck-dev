package com.chaekcheck.availability.internal.egress;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.HostThrottle;

/**
 * {@link LaneThrottle} kept in this process's memory. Correct only while the main service runs as one instance.
 * One monitor per pool: a caller looks through the lanes, starting after the lane the pool used last (so free lanes
 * take turns), for one with a free slot whose interval has passed; else it waits on the pool's monitor until the
 * earliest interval ends, a slot in the pool is released (every release notifies the pool's waiters), or the
 * deadline passes.
 */
public class InMemoryLaneThrottle implements LaneThrottle {

	private static final Logger log = LoggerFactory.getLogger(InMemoryLaneThrottle.class);

	private final Clock clock;

	private final Map<String, Pool> pools = new ConcurrentHashMap<>();

	public InMemoryLaneThrottle(Clock clock) {
		this.clock = clock;
	}

	@Override
	public <T> T run(List<EgressLane> lanes, URI uri, HostRules.Rule rule, Instant deadline, LaneCall<T> call) {
		if (lanes.isEmpty()) {
			throw UpstreamException.error("no egress lane for " + HostThrottle.hostPort(uri));
		}
		Pool pool = pools.computeIfAbsent(rule.poolKey(), k -> new Pool());
		Slot slot = acquire(pool, lanes, uri, rule, deadline);
		try {
			return call.call(slot.lane);
		}
		catch (RuntimeException ex) {
			throw ex;
		}
		catch (Exception ex) {
			throw new UpstreamException(UpstreamException.Kind.ERROR, "call to " + HostThrottle.hostPort(uri) + " failed",
					ex);
		}
		finally {
			synchronized (pool) {
				slot.state.inFlight--;
				slot.state.lastEnd = clock.instant();
				pool.notifyAll();
			}
		}
	}

	private Slot acquire(Pool pool, List<EgressLane> lanes, URI uri, HostRules.Rule rule, Instant deadline) {
		synchronized (pool) {
			while (true) {
				Instant now = clock.instant();
				Instant earliestReady = null;
				int start = 0;
				if (pool.lastLane != null) {
					for (int i = 0; i < lanes.size(); i++) {
						if (lanes.get(i).id().equals(pool.lastLane)) {
							start = i + 1;
							break;
						}
					}
				}
				for (int step = 0; step < lanes.size(); step++) {
					EgressLane lane = lanes.get((start + step) % lanes.size());
					LaneState state = pool.lanes.computeIfAbsent(lane.id(), id -> new LaneState());
					if (state.inFlight >= rule.maxConcurrency(lane)) {
						continue;
					}
					Instant ready = state.lastEnd == null ? now : state.lastEnd.plus(rule.minInterval());
					if (!ready.isAfter(now)) {
						state.inFlight++;
						pool.lastLane = lane.id();
						return new Slot(lane, state);
					}
					if (earliestReady == null || ready.isBefore(earliestReady)) {
						earliestReady = ready;
					}
				}
				Instant waitUntil = earliestReady == null || earliestReady.isAfter(deadline) ? deadline : earliestReady;
				long millis = Duration.between(now, waitUntil).toMillis();
				if (millis <= 0) {
					log.info("throttle timeout host={} lanes={} (no lane free before the deadline)",
							HostThrottle.hostPort(uri), lanes.size());
					throw UpstreamException.timeout("no free lane for " + HostThrottle.hostPort(uri) + " before the deadline");
				}
				try {
					pool.wait(millis);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
					throw new UpstreamException(UpstreamException.Kind.ERROR,
							"interrupted while waiting for " + HostThrottle.hostPort(uri), ex);
				}
			}
		}
	}

	private record Slot(EgressLane lane, LaneState state) {
	}

	private static final class Pool {

		final Map<String, LaneState> lanes = new ConcurrentHashMap<>();

		String lastLane;

	}

	private static final class LaneState {

		int inFlight;

		Instant lastEnd;

	}

}
