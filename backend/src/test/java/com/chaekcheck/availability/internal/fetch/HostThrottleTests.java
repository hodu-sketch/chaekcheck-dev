package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * AC-17 with short real durations: interval 300ms instead of 1s, calls take 200ms.
 */
class HostThrottleTests {

	static final Duration INTERVAL = Duration.ofMillis(300);

	static final Duration CALL = Duration.ofMillis(200);

	Clock clock = Clock.systemUTC();

	HostThrottle throttle = new InMemoryHostThrottle(1, INTERVAL, clock);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void sameHostCallsDoNotOverlapAndKeepTheInterval() {
		List<Span> spans = runConcurrently("https://a.test/", "https://a.test/");

		Span first = spans.get(0).start().isBefore(spans.get(1).start()) ? spans.get(0) : spans.get(1);
		Span second = first == spans.get(0) ? spans.get(1) : spans.get(0);
		assertThat(second.start()).isAfterOrEqualTo(first.end().plus(INTERVAL));
	}

	@Test
	void sameHostNameOnDifferentPortsSharesOneSlot() { // AC-18, ADR-0010
		List<Span> spans = runConcurrently("https://ebook.jbe.go.kr:10112/", "https://EBOOK.jbe.go.kr:10124/");

		Span first = spans.get(0).start().isBefore(spans.get(1).start()) ? spans.get(0) : spans.get(1);
		Span second = first == spans.get(0) ? spans.get(1) : spans.get(0);
		assertThat(second.start()).isAfterOrEqualTo(first.end().plus(INTERVAL));
	}

	@Test
	void differentHostsRunInParallel() {
		List<Span> spans = runConcurrently("https://a.test/", "https://b.test:446/");

		assertThat(Duration.between(spans.get(0).start(), spans.get(1).start()).abs()).isLessThan(CALL);
	}

	@Test
	void waitingForABusyHostPastTheDeadlineTimesOut() {
		CompletableFuture<Span> busy = CompletableFuture.supplyAsync(() -> call("https://a.test/", Duration.ofSeconds(5)),
				executor);
		sleep(Duration.ofMillis(50));

		assertThatThrownBy(() -> throttle.run(URI.create("https://a.test/"), clock.instant().plusMillis(100), () -> "never"))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));
		busy.join();
	}

	@Test
	void intervalThatPassesTheDeadlineTimesOutWithoutCalling() {
		call("https://a.test/", Duration.ofSeconds(5));

		assertThatThrownBy(() -> throttle.run(URI.create("https://a.test/"), clock.instant().plusMillis(100), () -> {
			throw new AssertionError("must not be called");
		})).isInstanceOfSatisfying(UpstreamException.class,
				e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));
	}

	private List<Span> runConcurrently(String urlA, String urlB) {
		CompletableFuture<Span> a = CompletableFuture.supplyAsync(() -> call(urlA, Duration.ofSeconds(5)), executor);
		CompletableFuture<Span> b = CompletableFuture.supplyAsync(() -> call(urlB, Duration.ofSeconds(5)), executor);
		return List.of(a.join(), b.join());
	}

	private Span call(String url, Duration deadline) {
		return throttle.run(URI.create(url), clock.instant().plus(deadline), () -> {
			Instant start = clock.instant();
			sleep(CALL);
			return new Span(start, clock.instant());
		});
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	record Span(Instant start, Instant end) {
	}

}
