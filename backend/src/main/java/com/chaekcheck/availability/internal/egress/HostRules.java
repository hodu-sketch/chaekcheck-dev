package com.chaekcheck.availability.internal.egress;

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * How much one lane may send to one library site (ADR-0094, ADR-0130, spec R13-3): by default one request at a
 * time per host name and {@code host-min-interval} (1s) after the previous one ended ({@code host-max-concurrency}
 * 1 — both values mean "per lane, per site" since webshare cycle (in cycle 20)). Kyobo subscription servers ({@code *.dkyobobook.co.kr},
 * usually 4-6s an answer) are one pool per lane: {@code kyobo.subscription-max-concurrency} (3) at a time across
 * all their host names, no interval. Counted per lane, so 21 lanes may send 21 requests a second to one site [확정].
 * <p>
 * Kyobo slow-down (ADR-0094 "느려지거나 연달아 실패하면 동시 1건", numbers [안: dev, collector values 12s / 5 as the
 * reference]): on one lane the pool drops to 1 at a time after {@link #KYOBO_SLOW_AFTER_FAILURES} failures in a row
 * or one answer slower than {@link #KYOBO_SLOW_RESPONSE}; one answer faster than that restores the setting.
 */
public final class HostRules {

	/** Kyobo answers slower than this (10s [안]) switch that lane's pool to one at a time. */
	public static final Duration KYOBO_SLOW_RESPONSE = Duration.ofSeconds(10);

	/** Kyobo failures in a row (3 [안]) that switch that lane's pool to one at a time. */
	public static final int KYOBO_SLOW_AFTER_FAILURES = 3;

	/** The slot pool of a host on a lane. */
	public interface Rule {

		/** The pool key: the host name, or one shared key for every Kyobo subscription host. */
		String poolKey();

		int maxConcurrency(EgressLane lane);

		/** Wait after the previous request in the pool ended. */
		Duration minInterval();

	}

	private final int maxConcurrency;

	private final Duration minInterval;

	private final int kyoboMaxConcurrency;

	private final String kyoboHostSuffix;

	private final Map<String, KyoboLane> kyoboLanes = new ConcurrentHashMap<>();

	/**
	 * @param kyoboHostSuffix host names ending with this (lower case, e.g. {@code .dkyobobook.co.kr}) are the Kyobo
	 * pool; empty = no Kyobo rule
	 */
	public HostRules(int maxConcurrency, Duration minInterval, int kyoboMaxConcurrency, String kyoboHostSuffix) {
		this.maxConcurrency = maxConcurrency;
		this.minInterval = minInterval;
		this.kyoboMaxConcurrency = kyoboMaxConcurrency;
		this.kyoboHostSuffix = kyoboHostSuffix == null ? "" : kyoboHostSuffix.toLowerCase(Locale.ROOT);
	}

	/** 1 at a time, 1s apart, Kyobo 3 at a time. */
	public static HostRules standard() {
		return new HostRules(1, Duration.ofSeconds(1), 3, ".dkyobobook.co.kr");
	}

	public Rule forHost(String host) {
		String lower = host.toLowerCase(Locale.ROOT);
		if (isKyobo(lower)) {
			String pool = kyoboHostSuffix.startsWith(".") ? kyoboHostSuffix.substring(1) : kyoboHostSuffix;
			return new Rule() {

				@Override
				public String poolKey() {
					return pool;
				}

				@Override
				public int maxConcurrency(EgressLane lane) {
					KyoboLane state = kyoboLanes.get(lane.id());
					return state != null && state.slow ? 1 : kyoboMaxConcurrency;
				}

				@Override
				public Duration minInterval() {
					return Duration.ZERO;
				}

			};
		}
		return new Rule() {

			@Override
			public String poolKey() {
				return lower;
			}

			@Override
			public int maxConcurrency(EgressLane lane) {
				return maxConcurrency;
			}

			@Override
			public Duration minInterval() {
				return minInterval;
			}

		};
	}

	/** One request to {@code host} on {@code lane} ended: feeds the Kyobo slow-down; other hosts ignore it. */
	public void ended(String host, EgressLane lane, boolean failed, long elapsedMs) {
		if (!isKyobo(host.toLowerCase(Locale.ROOT))) {
			return;
		}
		KyoboLane state = kyoboLanes.computeIfAbsent(lane.id(), id -> new KyoboLane());
		synchronized (state) {
			if (failed) {
				state.failures++;
				if (state.failures >= KYOBO_SLOW_AFTER_FAILURES) {
					state.slow = true;
				}
				return;
			}
			state.failures = 0;
			state.slow = elapsedMs >= KYOBO_SLOW_RESPONSE.toMillis();
		}
	}

	boolean isKyobo(String lowerHost) {
		return !kyoboHostSuffix.isEmpty() && lowerHost.endsWith(kyoboHostSuffix);
	}

	/** Whether the lane's Kyobo pool is at one at a time right now (tests, summary). */
	public boolean kyoboSlow(EgressLane lane) {
		KyoboLane state = kyoboLanes.get(lane.id());
		return state != null && state.slow;
	}

	private static final class KyoboLane {

		int failures;

		volatile boolean slow;

	}

}
