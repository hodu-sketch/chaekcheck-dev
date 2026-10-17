package com.chaekcheck.availability.internal.egress;

import java.util.List;

/**
 * Which lanes the lookups may use (ADR-0130, cycle 20 review #24, {@code from-cycle-20-proxy-only.md}):
 * {@code chaekcheck.availability.egress.mode} = {@code all} (default) / {@code proxy-only} / {@code direct-only}.
 * Proxies only means the direct lane is never used, probes of paused lanes included, and nothing falls back to it
 * when every proxy lane is paused: those lookups fail ("확인 불가").
 */
public enum EgressMode {

	ALL, PROXY_ONLY, DIRECT_ONLY;

	/**
	 * The lanes this mode allows, in the configured order.
	 * @throws IllegalArgumentException when the mode leaves no lane
	 */
	public List<EgressLane> allowed(List<EgressLane> lanes) {
		List<EgressLane> allowed = switch (this) {
			case ALL -> lanes;
			case PROXY_ONLY -> lanes.stream().filter(lane -> !lane.isDirect()).toList();
			case DIRECT_ONLY -> lanes.stream().filter(EgressLane::isDirect).toList();
		};
		if (allowed.isEmpty()) {
			throw new IllegalArgumentException("egress mode " + this + " leaves no lane in " + lanes);
		}
		return List.copyOf(allowed);
	}

}
