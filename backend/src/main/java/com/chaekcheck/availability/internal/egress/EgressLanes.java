package com.chaekcheck.availability.internal.egress;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.SiteHostNames;
import com.chaekcheck.availability.internal.fetch.SiteResponse;

/**
 * The egress lanes (webshare cycle (in cycle 20) / handoff 1-15, ADR-0130) and the one place a library request picks its lane: the lanes
 * the mode allows, minus those paused on this host, go to the {@link LaneThrottle}, which runs the request on the
 * first lane that can send now (per lane and site: 1 at a time + 1s, Kyobo 3 pooled), taking turns among free
 * lanes. The lane's {@link SiteFetcher} client sends; the answer's {@code via} is the lane id. Every outcome feeds
 * the {@link BlockedLanes} (block signals pause the lane on that host), the Kyobo slow-down in {@link HostRules}
 * and the {@link LaneCounters} (ops summary).
 * <p>
 * When every allowed lane is paused on a host the request fails at once with ERROR {@code no_lane_for_host} (the
 * library is {@code library-failed}, usage log FAILED, nothing cached, and the queue board sees no wait); in
 * {@code proxy-only} mode this is also what happens when every proxy lane is paused — the direct lane is never a
 * fallback, retests included ({@link BlockedLaneRetest}).
 * <p>
 * Hooks left for cycle 17 (vendor data quota, vendor-side errors): {@link #lanes()} knows each lane's vendor, so
 * "pause every lane of vendor X" is a filter in {@link #open}; the bytes a lane received are
 * {@code SiteResponse.body().length} in {@link #send} and the {@code upstream … lane= bytes=} log line.
 */
public final class EgressLanes {

	private static final Logger log = LoggerFactory.getLogger(EgressLanes.class);

	private final EgressMode mode;

	private final List<EgressLane> lanes;

	private final String vendor;

	private final HostRules rules;

	private final LaneThrottle throttle;

	private final BlockedLanes blocked;

	/** An answer that took this long counts like no answer for pausing the lane on that host; null = off */
	private final Duration slowAnswer;

	private final SiteFetcher siteFetcher;

	private final LaneCounters counters;

	private final Clock clock;

	/**
	 * @param allLanes every configured lane ({@link EgressLane#list}); {@code mode} picks the allowed ones and fails
	 * when none is left ({@code proxy-only} with an empty proxy list stops the start)
	 * @param slowAnswer an answer that took at least this long is a block signal like no answer (user decision
	 * 2026-10-18: a proxy that is slow on a site stops being used there); null = only failures count
	 * @param siteFetcher sends on the lane it is given (one client per lane behind it)
	 */
	public EgressLanes(EgressMode mode, List<EgressLane> allLanes, HostRules rules, LaneThrottle throttle,
			BlockedLanes blocked, Duration slowAnswer, SiteFetcher siteFetcher, LaneCounters counters, Clock clock) {
		this.mode = mode;
		this.lanes = mode.allowed(allLanes);
		this.vendor = lanes.stream().filter(lane -> !lane.isDirect()).map(EgressLane::vendor).findFirst().orElse("-");
		this.rules = rules;
		this.throttle = throttle;
		this.blocked = blocked;
		this.slowAnswer = slowAnswer;
		this.siteFetcher = siteFetcher;
		this.counters = counters;
		this.clock = clock;
		long proxies = lanes.stream().filter(lane -> !lane.isDirect()).count();
		// no addresses in this line
		log.info("egress lanes mode={} lanes={} direct={} proxies={} vendor={}", mode, lanes.size(),
				lanes.size() - proxies, proxies, vendor);
	}

	/** Every lane the mode allows, in order (the direct lane first when allowed). */
	public List<EgressLane> lanes() {
		return lanes;
	}

	public EgressMode mode() {
		return mode;
	}

	public BlockedLanes blocked() {
		return blocked;
	}

	public HostRules rules() {
		return rules;
	}

	/**
	 * GETs {@code uri} on the first lane that can send now.
	 * @param started told once a lane starts sending (the queue board's wait ends); may be null
	 * @throws UpstreamException TIMEOUT when no lane can start before {@code deadline} or the site did not answer in
	 * time; ERROR {@code no_lane_for_host} when every allowed lane is paused on this host; ERROR when the address
	 * has no host or the request failed
	 */
	public SiteResponse fetch(URI uri, Instant deadline, Runnable started) {
		String host = SiteHostNames.host(uri);
		List<EgressLane> open = open(host);
		if (open.isEmpty()) {
			throw UpstreamException.noLane("every egress lane is paused for " + host + " (" + lanes.size() + " lanes)");
		}
		return throttle.run(open, uri, rules.forHost(host), deadline, lane -> {
			if (started != null) {
				started.run();
			}
			return send(lane, host, uri, deadline, false);
		});
	}

	public SiteResponse fetch(URI uri, Instant deadline) {
		return fetch(uri, deadline, null);
	}

	/** The allowed lanes not paused on {@code host} right now. */
	List<EgressLane> open(String host) {
		Instant now = clock.instant();
		List<EgressLane> open = new ArrayList<>(lanes.size());
		for (EgressLane lane : lanes) {
			if (!blocked.isPaused(lane.id(), host, now)) {
				open.add(lane);
			}
		}
		return open;
	}

	/**
	 * One request on {@code lane}, with the lane bookkeeping (counters, blocked lanes, Kyobo slow-down). Called
	 * inside the throttle's slot.
	 * @param retest the one request that checks whether a paused lane is open again
	 */
	SiteResponse send(EgressLane lane, String host, URI uri, Instant deadline, boolean retest) {
		Instant start = clock.instant();
		counters.request(lane.id());
		try {
			SiteResponse response = siteFetcher.fetch(uri, deadline, lane);
			ended(lane, host, uri, BlockSignal.of(response) || slow(start), !response.isSuccess(), start, retest);
			return response.via() != null && response.via().equals(lane.id()) ? response
					: new SiteResponse(response.status(), response.contentType(), response.location(), response.body(),
							lane.id());
		}
		catch (UpstreamException ex) {
			ended(lane, host, uri, BlockSignal.of(ex), true, start, retest);
			throw ex;
		}
	}

	/** Whether a request that started at {@code start} took at least {@code slowAnswer}. */
	private boolean slow(Instant start) {
		return slowAnswer != null && Duration.between(start, clock.instant()).compareTo(slowAnswer) >= 0;
	}

	private void ended(EgressLane lane, String host, URI uri, boolean signal, boolean failed, Instant start,
			boolean retest) {
		Instant now = clock.instant();
		try {
			rules.ended(host, lane, failed, Duration.between(start, now).toMillis());
			if (retest) {
				BlockedLanes.Pause pause = blocked.retestEnded(lane.id(), host, signal, now);
				if (pause == null) {
					counters.resumed();
					log.info("lane_resumed host={} lane={} vendor={}", host, lane.id(), lane.vendor());
				}
				else {
					log.info("lane_retest host={} lane={} vendor={} result=blocked pause={} nextRetestAt={} retests={}", host,
							lane.id(), lane.vendor(), pause.length(), pause.until(), pause.retests());
					if (pause.capped()) {
						log.warn("lane_retest_capped host={} lane={} vendor={} pause={} nextRetestAt={} retests={}", host,
								lane.id(), lane.vendor(), pause.length(), pause.until(), pause.retests());
					}
				}
			}
			else {
				BlockedLanes.Pause pause = blocked.requestEnded(lane.id(), host, uri, signal, now);
				if (pause != null) {
					counters.pauseStarted();
					log.warn("lane_paused host={} lane={} vendor={} pause={} nextRetestAt={}", host, lane.id(),
							lane.vendor(), pause.length(), pause.until());
				}
			}
		}
		catch (RuntimeException ex) {
			log.warn("egress lane state not updated: {}", ex.toString());
		}
	}

	/** The lane with this id among the allowed ones, or null. */
	EgressLane lane(String id) {
		return lanes.stream().filter(lane -> lane.id().equals(id)).findFirst().orElse(null);
	}

	LaneThrottle throttle() {
		return throttle;
	}

	Clock clock() {
		return clock;
	}

	LaneCounters counters() {
		return counters;
	}

}
