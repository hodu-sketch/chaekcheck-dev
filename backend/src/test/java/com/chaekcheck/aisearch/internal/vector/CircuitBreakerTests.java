package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/** {@link CircuitBreaker}: opens after the failures in a row, skips while open, lets one trial through, then closes. */
class CircuitBreakerTests {

	final AtomicLong now = new AtomicLong();

	final CircuitBreaker breaker = new CircuitBreaker(3, Duration.ofSeconds(30), now::get);

	@Test
	void opensAfterThreeFailuresInARowOnly() {
		breaker.failure();
		breaker.failure();
		breaker.success();
		assertThat(breaker.failure()).isEqualTo(CircuitBreaker.Change.NONE);
		assertThat(breaker.failure()).isEqualTo(CircuitBreaker.Change.NONE);
		assertThat(breaker.failure()).isEqualTo(CircuitBreaker.Change.OPENED);
		assertThat(breaker.allow()).isFalse();
		assertThat(breaker.allow()).isFalse();
		assertThat(breaker.takeSkipped()).isEqualTo(2);
	}

	@Test
	void afterTheOpenTimeOneTrialGoesThrough() {
		for (int i = 0; i < 3; i++) {
			breaker.failure();
		}
		now.addAndGet(Duration.ofSeconds(30).toNanos());
		assertThat(breaker.allow()).isTrue();
		assertThat(breaker.allow()).isFalse(); // only one trial at a time
		assertThat(breaker.failure()).isEqualTo(CircuitBreaker.Change.OPENED); // the trial failed: open again
		assertThat(breaker.allow()).isFalse();
		now.addAndGet(Duration.ofSeconds(30).toNanos());
		assertThat(breaker.allow()).isTrue();
		assertThat(breaker.success()).isEqualTo(CircuitBreaker.Change.CLOSED);
		assertThat(breaker.allow()).isTrue();
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

}
