package com.chaekcheck.availability.internal.egress;

import java.time.Clock;

import com.chaekcheck.availability.internal.status.EgressLaneStatus;

/**
 * The lane code's answer to the status signal's read port (cycle 20 review #28, handoff 1-15 #7): the configured
 * mode and the number of paused (lane, host) pairs. {@link #vendorLanesPaused()} stays false [확정]: pausing every
 * lane of a vendor at once (vendor errors 407 / 429, account trouble, 90% of the data) is cycle 17's, so
 * {@code VENDOR_PAUSED} and {@code NO_LANE} never show yet; every lane paused on one site is not {@code NO_LANE}.
 */
public final class DefaultEgressLaneStatus implements EgressLaneStatus {

	private final EgressMode mode;

	private final BlockedLanes blocked;

	private final Clock clock;

	public DefaultEgressLaneStatus(EgressMode mode, BlockedLanes blocked, Clock clock) {
		this.mode = mode;
		this.blocked = blocked;
		this.clock = clock;
	}

	@Override
	public Mode egressMode() {
		return switch (mode) {
			case ALL -> Mode.ALL;
			case PROXY_ONLY -> Mode.PROXY_ONLY;
			case DIRECT_ONLY -> Mode.DIRECT_ONLY;
		};
	}

	@Override
	public boolean vendorLanesPaused() {
		// cycle 17 adds the vendor pause (data quota, vendor-side errors); per-host pauses are not a vendor pause
		return false;
	}

	/** Paused (lane, host) pairs right now [안: pairs, not lanes]. */
	@Override
	public int pausedLaneCount() {
		return blocked.pausedCount(clock.instant());
	}

}
