package com.chaekcheck.picks.internal.schedule;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Optional;

import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * Whether to fetch now (spec R1-2, R1-3, R1-5, ADR-0092), in Asia/Seoul, at 05:00 and at start-up:
 * <ol>
 * <li>a fetch succeeded today already: do not fetch [안]</li>
 * <li>no list stored: fetch ({@code STARTUP} at start-up, {@code CATCH_UP} at 05:00 [안])</li>
 * <li>05:00 on a Monday: fetch ({@code SCHEDULED})</li>
 * <li>05:00 and the latest run failed (no success after it): fetch ({@code RETRY}), every day until one succeeds</li>
 * <li>the latest success is more than 7 days old, or there is none: fetch ({@code CATCH_UP})</li>
 * <li>otherwise do not fetch</li>
 * </ol>
 * At start-up only rules 1, 2 and 5 apply [안]: a failure is tried again at 05:00, not at every restart.
 */
public class FetchDecision {

	/** A success older than this is caught up [확정 7 days]. */
	static final Duration CATCH_UP_AFTER = Duration.ofDays(7);

	private final PicksStore store;

	private final Clock clock;

	private final ZoneId zone;

	public FetchDecision(PicksStore store, Clock clock, ZoneId zone) {
		this.store = store;
		this.clock = clock;
		this.zone = zone;
	}

	/**
	 * @param atStartup the decision at start-up; false for the daily 05:00 one
	 * @return the trigger to fetch with now, or empty
	 */
	public Optional<FetchTrigger> decide(boolean atStartup) {
		Instant now = clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		Optional<Instant> lastSuccess = store.lastSucceededAt();
		if (lastSuccess.isPresent() && LocalDate.ofInstant(lastSuccess.get(), zone).equals(today)) {
			return Optional.empty();
		}
		if (!store.hasPicks()) {
			return Optional.of(atStartup ? FetchTrigger.STARTUP : FetchTrigger.CATCH_UP);
		}
		if (!atStartup && today.getDayOfWeek() == DayOfWeek.MONDAY) {
			return Optional.of(FetchTrigger.SCHEDULED);
		}
		if (!atStartup && store.lastStatus().filter("FAILED"::equals).isPresent()) {
			return Optional.of(FetchTrigger.RETRY);
		}
		if (lastSuccess.isEmpty() || lastSuccess.get().isBefore(now.minus(CATCH_UP_AFTER))) {
			return Optional.of(FetchTrigger.CATCH_UP);
		}
		return Optional.empty();
	}

}
