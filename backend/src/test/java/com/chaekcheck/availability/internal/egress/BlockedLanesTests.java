package com.chaekcheck.availability.internal.egress;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

/** The block and retest rule on its own ({@code from-cycle-20-blocked-ip-resume.md}): 5 in a row, 10 min, doubling, 7 days. */
class BlockedLanesTests {

	static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");

	static final URI URL = URI.create("https://a.test/search?q=x");

	InMemoryBlockedLanes blocked = new InMemoryBlockedLanes(5, Duration.ofMinutes(10), Duration.ofDays(7));

	@Test
	void fiveSignalsInARowPauseTheLaneOnThatHostOnly() {
		for (int i = 0; i < 4; i++) {
			assertThat(blocked.requestEnded("proxy-01", "a.test", URL, true, T0)).isNull();
		}
		assertThat(blocked.isPaused("proxy-01", "a.test", T0)).isFalse();

		BlockedLanes.Pause pause = blocked.requestEnded("proxy-01", "a.test", URL, true, T0);

		assertThat(pause).isNotNull();
		assertThat(pause.until()).isEqualTo(T0.plus(Duration.ofMinutes(10)));
		assertThat(blocked.isPaused("proxy-01", "a.test", T0)).isTrue();
		assertThat(blocked.isPaused("proxy-01", "b.test", T0)).isFalse();
		assertThat(blocked.isPaused("direct", "a.test", T0)).isFalse();
		assertThat(blocked.pausedCount(T0)).isEqualTo(1);
	}

	@Test
	void anyOtherOutcomeStartsTheCountAgain() {
		for (int i = 0; i < 4; i++) {
			blocked.requestEnded("proxy-01", "a.test", URL, true, T0);
		}
		blocked.requestEnded("proxy-01", "a.test", URL, false, T0);
		for (int i = 0; i < 4; i++) {
			assertThat(blocked.requestEnded("proxy-01", "a.test", URL, true, T0)).isNull();
		}
		assertThat(blocked.isPaused("proxy-01", "a.test", T0)).isFalse();
	}

	@Test
	void retestsDoubleThePauseUpToSevenDaysAndASuccessResumes() {
		for (int i = 0; i < 5; i++) {
			blocked.requestEnded("proxy-01", "a.test", URL, true, T0);
		}
		Instant now = T0;
		Duration expected = Duration.ofMinutes(10);
		for (int round = 0; round < 12; round++) {
			assertThat(blocked.dueRetests(now.plus(expected).minusSeconds(1))).as("round " + round).isEmpty();
			now = now.plus(expected);
			var due = blocked.dueRetests(now);
			assertThat(due).hasSize(1);
			assertThat(due.get(0).uri()).isEqualTo(URL);
			assertThat(blocked.dueRetests(now)).as("one retest in flight").isEmpty();
			assertThat(blocked.isPaused("proxy-01", "a.test", now)).as("still paused while retesting").isTrue();
			BlockedLanes.Pause pause = blocked.retestEnded("proxy-01", "a.test", true, now);
			expected = expected.multipliedBy(2).compareTo(Duration.ofDays(7)) > 0 ? Duration.ofDays(7)
					: expected.multipliedBy(2);
			assertThat(pause.length()).isEqualTo(expected);
			assertThat(pause.until()).isEqualTo(now.plus(expected));
			assertThat(pause.capped()).isEqualTo(expected.equals(Duration.ofDays(7)));
		}
		// 10 → 20 → 40 → 80 → 160 → 320 → 640 → 1280 → 2560 → 5120 min (3.6 d) → 7 d → 7 d
		assertThat(expected).isEqualTo(Duration.ofDays(7));

		now = now.plus(expected);
		assertThat(blocked.dueRetests(now)).hasSize(1);
		assertThat(blocked.retestEnded("proxy-01", "a.test", false, now)).isNull();
		assertThat(blocked.isPaused("proxy-01", "a.test", now)).isFalse();
		assertThat(blocked.pausedCount(now)).isZero();
	}

}
