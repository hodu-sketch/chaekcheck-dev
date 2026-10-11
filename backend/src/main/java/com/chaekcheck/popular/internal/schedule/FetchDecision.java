package com.chaekcheck.popular.internal.schedule;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import com.chaekcheck.popular.internal.store.PopularStore;

/**
 * Whether to fetch today, and which month (plan cycle 10 design #7 to #9, spec R1-2, R1-3, R1-6), in Asia/Seoul:
 * <ol>
 * <li>no month stored at all: fetch (also on the 1st or 2nd, and every day until one fetch succeeds)</li>
 * <li>the target is last month</li>
 * <li>it was stored after the 3rd of this month 00:00: do not fetch</li>
 * <li>it failed twice since the 3rd of this month 00:00: do not fetch (the month before stays)</li>
 * <li>today is the 3rd or later: fetch</li>
 * <li>otherwise do not fetch</li>
 * </ol>
 * Last month's sales are complete on the afternoon of the 2nd (plan research kpipa.md "당월 값과 지난달 값"), so a month
 * stored on the 1st or 2nd is fetched again on the 3rd.
 */
public class FetchDecision {

	/** Day of the month from which last month counts as complete. */
	static final int COMPLETE_FROM_DAY = 3;

	/** Failed fetches of a month after which that month is given up. */
	static final int MAX_FAILURES = 2;

	private final PopularStore store;

	private final Clock clock;

	private final ZoneId zone;

	public FetchDecision(PopularStore store, Clock clock, ZoneId zone) {
		this.store = store;
		this.clock = clock;
		this.zone = zone;
	}

	/** @return the month to fetch now, or empty */
	public Optional<YearMonth> monthToFetch() {
		LocalDate today = LocalDate.now(clock.withZone(zone));
		YearMonth target = YearMonth.from(today).minusMonths(1);
		if (store.latestMonth().isEmpty()) {
			return Optional.of(target);
		}
		Instant complete = today.withDayOfMonth(COMPLETE_FROM_DAY).atStartOfDay(zone).toInstant();
		Optional<Instant> stored = store.fetchedAt(target);
		if (stored.isPresent() && !stored.get().isBefore(complete)) {
			return Optional.empty();
		}
		if (store.failuresSince(target, complete) >= MAX_FAILURES) {
			return Optional.empty();
		}
		return today.getDayOfMonth() >= COMPLETE_FROM_DAY ? Optional.of(target) : Optional.empty();
	}

}
