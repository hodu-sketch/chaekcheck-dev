package com.chaekcheck.aisearch.internal.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.chaekcheck.aisearch.internal.quota.AiSearchQuota.Decision;

/**
 * {@link InMemoryAiSearchQuota} with a set clock (cycle 24 handoff step 7, AC-8, AC-19): the 11th search in a minute
 * and the 101st in a day are refused with their Retry-After, the counts clear when the minute passes and at Seoul
 * midnight, IPs are counted apart, and a new instance (a restart) starts again.
 */
class InMemoryAiSearchQuotaTests {

	private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-10-24 14:00 in Seoul. */
	private final SetClock clock = new SetClock(Instant.parse("2026-10-24T05:00:00Z"));

	private final InMemoryAiSearchQuota quota = new InMemoryAiSearchQuota(10, 100, SEOUL, clock);

	@Test
	void theEleventhInAMinuteWaitsForTheOldestToLeave() {
		for (int i = 0; i < 10; i++) {
			assertThat(quota.tryAcquire("203.0.113.7").allowed()).isTrue();
			clock.add(Duration.ofSeconds(2));
		}

		Decision eleventh = quota.tryAcquire("203.0.113.7");

		assertThat(eleventh.allowed()).isFalse();
		assertThat(eleventh.retryAfterSeconds()).isEqualTo(40);
		assertThat(quota.tryAcquire("198.51.100.1").allowed()).isTrue();
		clock.add(Duration.ofSeconds(40));
		assertThat(quota.tryAcquire("203.0.113.7").allowed()).isTrue();
	}

	@Test
	void theHundredAndFirstInADayWaitsForSeoulMidnight() {
		for (int i = 0; i < 100; i++) {
			assertThat(quota.tryAcquire("203.0.113.7").allowed()).as("search %d", i + 1).isTrue();
			clock.add(Duration.ofSeconds(7));
		}

		Decision refused = quota.tryAcquire("203.0.113.7");

		assertThat(refused.allowed()).isFalse();
		Instant midnight = Instant.parse("2026-10-24T15:00:00Z");
		assertThat(refused.retryAfterSeconds()).isEqualTo(Duration.between(clock.instant(), midnight).toSeconds());
		clock.set(midnight);
		assertThat(quota.tryAcquire("203.0.113.7").allowed()).isTrue();
	}

	@Test
	void aRestartStartsEveryCountAgain() {
		for (int i = 0; i < 10; i++) {
			quota.tryAcquire("203.0.113.7");
		}
		assertThat(quota.tryAcquire("203.0.113.7").allowed()).isFalse();

		InMemoryAiSearchQuota restarted = new InMemoryAiSearchQuota(10, 100, SEOUL, clock);

		assertThat(restarted.tryAcquire("203.0.113.7").allowed()).isTrue();
	}

	@Test
	void earlierDaysIpsAreDropped() {
		quota.tryAcquire("203.0.113.7");
		clock.add(Duration.ofDays(1));
		for (int i = 1; i < InMemoryAiSearchQuota.SWEEP_EVERY; i++) {
			quota.tryAcquire("198.51.100.1");
		}

		assertThat(quota.size()).isEqualTo(1);
	}

	@Test
	void noIpSharesOneCount() {
		for (int i = 0; i < 10; i++) {
			quota.tryAcquire(null);
		}

		assertThat(quota.tryAcquire("").allowed()).isFalse();
	}

	static final class SetClock extends Clock {

		private Instant now;

		SetClock(Instant now) {
			this.now = now;
		}

		void add(Duration duration) {
			now = now.plus(duration);
		}

		void set(Instant instant) {
			now = instant;
		}

		@Override
		public Instant instant() {
			return now;
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

	}

}
