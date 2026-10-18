package com.chaekcheck.availability.internal.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Per lane and site call spacing (spec R13-3, ADR-0094 Kyobo pool, 17 AC-8 taking turns; cycle 01 AC-17 / AC-18 kept
 * per lane), with short real durations: interval 300ms instead of 1s, calls take 200ms.
 */
class LaneThrottleTests {

	static final Duration INTERVAL = Duration.ofMillis(300);

	static final Duration CALL = Duration.ofMillis(200);

	static final List<EgressLane> THREE = EgressLane.list("p1:1,p2:2", "webshare");

	Clock clock = Clock.systemUTC();

	HostRules rules = new HostRules(1, INTERVAL, 3, ".dkyobobook.co.kr");

	LaneThrottle throttle = new InMemoryLaneThrottle(clock);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void threeLanesSendThreeRequestsToOneSiteAtOnce() { // R13-3
		List<Span> spans = runConcurrently(THREE, "https://a.test/1", "https://a.test/2", "https://a.test/3");

		assertThat(spans).extracting(Span::lane).containsExactlyInAnyOrder("direct", "proxy-01", "proxy-02");
		Instant latestStart = spans.stream().map(Span::start).max(Instant::compareTo).orElseThrow();
		Instant earliestEnd = spans.stream().map(Span::end).min(Instant::compareTo).orElseThrow();
		assertThat(latestStart).isBefore(earliestEnd);
	}

	@Test
	void sameLaneKeepsTheIntervalWhileOtherSitesDoNotWait() { // AC-17 per lane
		List<EgressLane> one = List.of(EgressLane.DIRECT);
		List<Span> same = runConcurrently(one, "https://a.test/", "https://a.test/");
		Span first = same.get(0).start().isBefore(same.get(1).start()) ? same.get(0) : same.get(1);
		Span second = first == same.get(0) ? same.get(1) : same.get(0);
		assertThat(second.start()).isAfterOrEqualTo(first.end().plus(INTERVAL));

		List<Span> other = runConcurrently(one, "https://b.test/", "https://c.test/");
		assertThat(other.get(0).start()).isBefore(other.get(1).end());
		assertThat(other.get(1).start()).isBefore(other.get(0).end());
	}

	@Test
	void sameHostNameOnDifferentPortsSharesOneSlot() { // AC-18, ADR-0010
		List<Span> spans = runConcurrently(List.of(EgressLane.DIRECT), "https://ebook.jbe.go.kr:10112/",
				"https://EBOOK.jbe.go.kr:10124/");

		Span first = spans.get(0).start().isBefore(spans.get(1).start()) ? spans.get(0) : spans.get(1);
		Span second = first == spans.get(0) ? spans.get(1) : spans.get(0);
		assertThat(second.start()).isAfterOrEqualTo(first.end().plus(INTERVAL));
	}

	@Test
	void freeLanesTakeTurns() { // 17 AC-8 "번갈아"
		HostRules noInterval = new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr");
		List<String> lanes = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			lanes.add(throttle.run(THREE, URI.create("https://a.test/"), noInterval.forHost("a.test"), Instant.MAX,
					lane -> lane.id()));
		}

		// the starting lane comes from the site's name; from there the lanes take turns in order
		List<String> cycle = List.of("direct", "proxy-01", "proxy-02");
		int first = cycle.indexOf(lanes.get(0));
		assertThat(first).isNotNegative();
		for (int i = 0; i < lanes.size(); i++) {
			assertThat(lanes.get(i)).isEqualTo(cycle.get((first + i) % cycle.size()));
		}
	}

	@Test
	void differentSitesStartOnDifferentLanes() { // user 2026-10-18: "사이트마다 시작 길 다르게 흩뿌리는 거"
		HostRules noInterval = new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr");
		java.util.Set<String> firstLanes = new java.util.HashSet<>();
		for (int i = 0; i < 30; i++) {
			String host = "site" + i + ".test";
			firstLanes.add(throttle.run(THREE, URI.create("https://" + host + "/"), noInterval.forHost(host),
					Instant.MAX, lane -> lane.id()));
		}
		assertThat(firstLanes).containsExactlyInAnyOrder("direct", "proxy-01", "proxy-02");
	}

	@Test
	void kyoboHostsArePooledAtThreePerLaneWithoutInterval() { // ADR-0094
		List<EgressLane> one = List.of(EgressLane.DIRECT);
		List<Span> spans = new ArrayList<>(runConcurrently(one, "https://x.dkyobobook.co.kr/",
				"https://y.dkyobobook.co.kr/", "https://x.dkyobobook.co.kr/2", "https://y.dkyobobook.co.kr/2"));

		spans.sort((a, b) -> a.start().compareTo(b.start()));
		// three at once, the fourth right after one ends (no 300ms interval)
		assertThat(spans.get(2).start()).isBefore(spans.get(0).end());
		Instant firstEnd = spans.stream().map(Span::end).min(Instant::compareTo).orElseThrow();
		assertThat(spans.get(3).start()).isAfterOrEqualTo(firstEnd).isBefore(firstEnd.plus(INTERVAL));

		List<Span> twoLanes = runConcurrently(EgressLane.list("p1:1", "webshare"), "https://x.dkyobobook.co.kr/a",
				"https://y.dkyobobook.co.kr/b", "https://x.dkyobobook.co.kr/c", "https://y.dkyobobook.co.kr/d",
				"https://x.dkyobobook.co.kr/e", "https://y.dkyobobook.co.kr/f");
		Instant latestStart = twoLanes.stream().map(Span::start).max(Instant::compareTo).orElseThrow();
		Instant earliestEnd = twoLanes.stream().map(Span::end).min(Instant::compareTo).orElseThrow();
		assertThat(latestStart).isBefore(earliestEnd);
	}

	@Test
	void kyoboLaneDropsToOneAtATimeAfterFailuresInARow() { // ADR-0094 "연달아 실패하면 동시 1건"
		for (int i = 0; i < HostRules.KYOBO_SLOW_AFTER_FAILURES; i++) {
			rules.ended("x.dkyobobook.co.kr", EgressLane.DIRECT, true, 100);
		}
		assertThat(rules.kyoboSlow(EgressLane.DIRECT)).isTrue();
		assertThat(rules.forHost("y.dkyobobook.co.kr").maxConcurrency(EgressLane.DIRECT)).isEqualTo(1);

		rules.ended("x.dkyobobook.co.kr", EgressLane.DIRECT, false, 100);
		assertThat(rules.kyoboSlow(EgressLane.DIRECT)).isFalse();
		rules.ended("x.dkyobobook.co.kr", EgressLane.DIRECT, false, HostRules.KYOBO_SLOW_RESPONSE.toMillis());
		assertThat(rules.kyoboSlow(EgressLane.DIRECT)).as("a slow answer").isTrue();
		assertThat(rules.forHost("other.test").maxConcurrency(EgressLane.DIRECT)).isEqualTo(1);
	}

	@Test
	void noLaneFreeBeforeTheDeadlineIsATimeout() {
		AtomicInteger calls = new AtomicInteger();
		CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> throttle.run(List.of(EgressLane.DIRECT),
				URI.create("https://a.test/"), rules.forHost("a.test"), Instant.MAX, lane -> {
					calls.incrementAndGet();
					Thread.sleep(600);
					return null;
				}), executor);
		while (calls.get() == 0) {
			Thread.onSpinWait();
		}

		assertThatThrownBy(() -> throttle.run(List.of(EgressLane.DIRECT), URI.create("https://a.test/"),
				rules.forHost("a.test"), clock.instant().plusMillis(100), lane -> null))
			.isInstanceOf(UpstreamException.class)
			.satisfies(ex -> assertThat(((UpstreamException) ex).kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));
		holder.join();
	}

	private List<Span> runConcurrently(List<EgressLane> lanes, String... urls) {
		List<CompletableFuture<Span>> futures = new ArrayList<>();
		for (String url : urls) {
			URI uri = URI.create(url);
			futures.add(CompletableFuture.supplyAsync(() -> throttle.run(lanes, uri,
					rules.forHost(uri.getHost()), clock.instant().plusSeconds(10), lane -> {
						Instant start = clock.instant();
						Thread.sleep(CALL);
						return new Span(lane.id(), start, clock.instant());
					}), executor));
		}
		return futures.stream().map(CompletableFuture::join).toList();
	}

	record Span(String lane, Instant start, Instant end) {
	}

}
