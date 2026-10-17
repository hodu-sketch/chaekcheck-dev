package com.chaekcheck.availability.internal.egress;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Retests paused lanes ({@code from-cycle-20-blocked-ip-resume.md}, handoff 1-15 #4): every
 * {@code egress.block.retest-check-interval} it asks the {@link BlockedLanes} for pauses that ran out and sends one
 * GET per (lane, host) to the address that was blocked, through that lane's own throttle slot (1 at a time + 1s)
 * and within {@code retest-timeout}. A 2xx resumes the lane ({@code lane_resumed}), a block signal doubles the pause
 * ({@code lane_retest … result=blocked}, {@code lane_retest_capped} at 7 days). Only lanes the mode allows are
 * retested: in {@code proxy-only} the direct lane is never touched. Its own daemon thread like the other timers
 * (no dependency on Spring scheduling); tests call {@link #retestDue()} with a fake clock.
 */
public final class BlockedLaneRetest implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(BlockedLaneRetest.class);

	private final EgressLanes lanes;

	private final Duration retestTimeout;

	private final ScheduledExecutorService timer;

	private final ExecutorService runner = Executors.newVirtualThreadPerTaskExecutor();

	/** @param checkInterval zero = no timer (tests) */
	public BlockedLaneRetest(EgressLanes lanes, Duration checkInterval, Duration retestTimeout) {
		this.lanes = lanes;
		this.retestTimeout = retestTimeout;
		if (checkInterval.isZero()) {
			this.timer = null;
		}
		else {
			this.timer = Executors.newSingleThreadScheduledExecutor(
					Thread.ofPlatform().name("egress-lane-retest").daemon(true).factory());
			long millis = checkInterval.toMillis();
			timer.scheduleAtFixedRate(this::retestDue, millis, millis, TimeUnit.MILLISECONDS);
		}
	}

	/** Starts a retest for every pause that has run out; returns how many were started. */
	public int retestDue() {
		int started = 0;
		try {
			for (BlockedLanes.Retest retest : lanes.blocked().dueRetests(lanes.clock().instant())) {
				EgressLane lane = lanes.lane(retest.laneId());
				if (lane == null) {
					// a lane the mode does not allow (paused before a restart with another setting): never retested
					continue;
				}
				started++;
				runner.execute(() -> retest(lane, retest));
			}
		}
		catch (RuntimeException ex) {
			log.warn("lane retests not started: {}", ex.toString());
		}
		return started;
	}

	/** Runs the retests on the calling thread (tests). */
	public void retestDueNow() {
		for (BlockedLanes.Retest retest : lanes.blocked().dueRetests(lanes.clock().instant())) {
			EgressLane lane = lanes.lane(retest.laneId());
			if (lane != null) {
				retest(lane, retest);
			}
		}
	}

	private void retest(EgressLane lane, BlockedLanes.Retest retest) {
		lanes.counters().retestSent();
		log.info("lane_retest host={} lane={} vendor={} result=sending afterPause={} retests={}", retest.host(), lane.id(),
				lane.vendor(), retest.pause(), retest.retests());
		Instant deadline = lanes.clock().instant().plus(retestTimeout);
		try {
			lanes.throttle().runOn(lane, retest.uri(), lanes.rules().forHost(retest.host()), deadline,
					() -> lanes.send(lane, retest.host(), retest.uri(), deadline, true));
		}
		catch (UpstreamException ex) {
			if (ex.getMessage() != null && ex.getMessage().startsWith("no free lane")) {
				// the slot did not free up in time: the pause doubles like a blocked answer, as nothing was learned
				BlockedLanes.Pause pause = lanes.blocked().retestEnded(lane.id(), retest.host(), true,
						lanes.clock().instant());
				if (pause != null) {
					log.info("lane_retest host={} lane={} vendor={} result=no_slot pause={} nextRetestAt={} retests={}",
							retest.host(), lane.id(), lane.vendor(), pause.length(), pause.until(), pause.retests());
				}
			}
			// else: already recorded and logged by EgressLanes.send
		}
		catch (RuntimeException ex) {
			log.warn("lane_retest host={} lane={} result=error: {}", retest.host(), lane.id(), ex.toString());
		}
	}

	@Override
	public void close() {
		if (timer != null) {
			timer.shutdownNow();
		}
		runner.shutdownNow();
	}

}
