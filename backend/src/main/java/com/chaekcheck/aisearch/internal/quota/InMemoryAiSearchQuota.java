package com.chaekcheck.aisearch.internal.quota;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link AiSearchQuota} in this server's memory: per IP the start times of the last minute and the day's count (the day
 * of {@code chaekcheck.aisearch.zone}). A restart starts every count again (ADR-0152, accepted). The IPs of earlier days
 * are dropped every {@value #SWEEP_EVERY} calls.
 */
@Component
class InMemoryAiSearchQuota implements AiSearchQuota {

	static final int SWEEP_EVERY = 1_000;

	private static final Duration MINUTE = Duration.ofMinutes(1);

	private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

	private final AtomicInteger calls = new AtomicInteger();

	private final int perMinute;

	private final int perDay;

	private final ZoneId zone;

	private final Clock clock;

	@Autowired
	InMemoryAiSearchQuota(AiSearchProperties properties) {
		this(properties.rate().perMinute(), properties.rate().perDay(), properties.zone(), Clock.systemUTC());
	}

	InMemoryAiSearchQuota(int perMinute, int perDay, ZoneId zone, Clock clock) {
		this.perMinute = perMinute;
		this.perDay = perDay;
		this.zone = zone;
		this.clock = clock;
	}

	@Override
	public Decision tryAcquire(String ip) {
		Instant now = clock.instant();
		LocalDate today = LocalDate.ofInstant(now, zone);
		if (calls.incrementAndGet() % SWEEP_EVERY == 0) {
			counters.values().removeIf(counter -> counter.stale(today, now));
		}
		Counter counter = counters.computeIfAbsent(ip == null ? "" : ip, key -> new Counter());
		return counter.tryAcquire(now, today);
	}

	/** How many IPs are counted now (tests). */
	int size() {
		return counters.size();
	}

	private final class Counter {

		private final Deque<Instant> lastMinute = new ArrayDeque<>();

		private LocalDate day;

		private int dayCount;

		synchronized Decision tryAcquire(Instant now, LocalDate today) {
			while (!lastMinute.isEmpty() && !lastMinute.peekFirst().plus(MINUTE).isAfter(now)) {
				lastMinute.pollFirst();
			}
			if (!today.equals(day)) {
				day = today;
				dayCount = 0;
			}
			if (dayCount >= perDay) {
				Instant nextDay = today.plusDays(1).atStartOfDay(zone).toInstant();
				return new Decision(false, ceilSeconds(Duration.between(now, nextDay)));
			}
			if (lastMinute.size() >= perMinute) {
				Instant free = lastMinute.peekFirst().plus(MINUTE);
				return new Decision(false, ceilSeconds(Duration.between(now, free)));
			}
			lastMinute.addLast(now);
			dayCount++;
			return Decision.ALLOWED;
		}

		synchronized boolean stale(LocalDate today, Instant now) {
			return !today.equals(day) && (lastMinute.isEmpty() || !lastMinute.peekLast().plus(MINUTE).isAfter(now));
		}

	}

	private static long ceilSeconds(Duration duration) {
		long seconds = duration.toSeconds();
		return Math.max(1, duration.toNanosPart() > 0 ? seconds + 1 : seconds);
	}

}
