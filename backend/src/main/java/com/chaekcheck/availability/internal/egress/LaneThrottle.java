package com.chaekcheck.availability.internal.egress;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Call spacing per lane and site (ADR-0010 made per lane by ADR-0095/0130, handoff 1-15 #3): in one lane's pool
 * for a site ({@link HostRules.Rule#poolKey()}: the host name, port ignored; Kyobo subscription hosts share one)
 * at most {@link HostRules.Rule#maxConcurrency} calls at a time, and a call starts no earlier than
 * {@link HostRules.Rule#minInterval()} after the previous call in that pool ended. Waiting counts toward the
 * caller's deadline; a call that cannot start before it fails with TIMEOUT.
 * <p>
 * Behind an interface so a shared implementation can replace the in-memory one when the main service runs on
 * several instances (plan-feedback C2-U-1, ADR-0133).
 */
public interface LaneThrottle {

	/**
	 * Runs {@code call} on one of {@code lanes} that can send to the site now, taking turns among the free ones
	 * (17 AC-8 "번갈아"); when none can, waits for the first one to become free.
	 * @throws com.chaekcheck.availability.internal.UpstreamException TIMEOUT when no lane can start before
	 * {@code deadline}, ERROR when the call throws a checked exception
	 */
	<T> T run(List<EgressLane> lanes, URI uri, HostRules.Rule rule, Instant deadline, LaneCall<T> call);

	/** The same on one given lane (the retest of a paused lane uses the lane's own slot). */
	default <T> T runOn(EgressLane lane, URI uri, HostRules.Rule rule, Instant deadline, Callable<T> call) {
		return run(List.of(lane), uri, rule, deadline, picked -> call.call());
	}

	@FunctionalInterface
	interface LaneCall<T> {

		T call(EgressLane lane) throws Exception;

	}

}
