package com.chaekcheck.availability.internal.egress;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * Which lanes are paused on which library hosts (spec R13-4, {@code from-cycle-20-blocked-ip-resume.md}, handoff
 * 1-15 #4): a lane a host has blocked stops sending to that host only. Behind a store interface like the rest of the
 * memory state (ADR-0133); {@link InMemoryBlockedLanes} is the {@code memory} implementation and holds the rule:
 * <ul>
 * <li>A lane is blocked on a host after {@code egress.block.failures-to-block} (5) block signals in a row on that
 * lane and host ({@link BlockSignal}: no answer, or 403 / 407 / 429 / 503); any other outcome starts the count again.
 * One answer over the body limit, one parse failure or one timeout alone never pauses a lane [안: dev, ADR-0094
 * "기준 숫자는 dev가 정함"].</li>
 * <li>The first pause is {@code first-pause} (10 min). When it ends, {@link BlockedLaneRetest} sends one request
 * through the lane's own slot; a 2xx resumes the lane, a block signal doubles the pause (10 → 20 → 40 min …) up to
 * {@code max-pause} (7 days) [확정].</li>
 * <li>Users' lookups never go through a paused lane; the retest is the only request.</li>
 * </ul>
 */
public interface BlockedLanes {

	boolean isPaused(String laneId, String host, Instant now);

	/**
	 * One ordinary request on {@code laneId} to {@code host} ended.
	 * @param uri the address requested; kept as the retest address when this outcome starts a pause
	 * @return the pause this outcome started, or null
	 */
	Pause requestEnded(String laneId, String host, URI uri, boolean blockSignal, Instant now);

	/**
	 * A retest on a paused lane ended.
	 * @return the lengthened pause, or null when the lane resumed
	 */
	Pause retestEnded(String laneId, String host, boolean blockSignal, Instant now);

	/** Pauses that have run out and have no retest in flight; each is marked as retesting before it is returned. */
	List<Retest> dueRetests(Instant now);

	/** The (lane, host) pairs paused at {@code now}, for the summary line. */
	List<Paused> paused(Instant now);

	default int pausedCount(Instant now) {
		return paused(now).size();
	}

	/**
	 * @param length the pause that started, {@code until} when it ends and a retest is due
	 * @param capped the pause is at {@code max-pause}
	 */
	record Pause(String laneId, String host, Instant until, Duration length, int retests, boolean capped) {
	}

	record Retest(String laneId, String host, URI uri, Duration pause, int retests) {
	}

	record Paused(String laneId, String host, Instant nextRetestAt, int retests) {
	}

}
