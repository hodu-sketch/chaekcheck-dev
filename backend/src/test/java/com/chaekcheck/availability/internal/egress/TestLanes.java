package com.chaekcheck.availability.internal.egress;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

import com.chaekcheck.availability.internal.fetch.SiteFetcher;

/** Egress lanes for tests of the page fetchers: the direct lane only, no interval, over a given (usually mocked) {@link SiteFetcher}. */
public final class TestLanes {

	private TestLanes() {
	}

	public static EgressLanes direct(SiteFetcher siteFetcher, Clock clock) {
		return of(EgressMode.DIRECT_ONLY, List.of(EgressLane.DIRECT), new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr"),
				new InMemoryLaneThrottle(clock), siteFetcher, clock);
	}

	public static EgressLanes of(EgressMode mode, List<EgressLane> lanes, HostRules rules, LaneThrottle throttle,
			SiteFetcher siteFetcher, Clock clock) {
		return new EgressLanes(mode, lanes, rules, throttle, new InMemoryBlockedLanes(5, Duration.ofMinutes(10), Duration.ofDays(7)),
				siteFetcher, new LaneCounters(lanes), clock);
	}

}
