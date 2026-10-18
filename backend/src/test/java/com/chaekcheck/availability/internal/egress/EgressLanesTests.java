package com.chaekcheck.availability.internal.egress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.SiteResponse;

/**
 * Lane choice, paused lanes and retests over a fake site fetcher and a fake clock (17 AC-8, AC-9, R13-4,
 * {@code from-cycle-20-proxy-only.md}, {@code from-cycle-20-blocked-ip-resume.md}).
 */
@ExtendWith(OutputCaptureExtension.class)
class EgressLanesTests {

	static final Instant T0 = Instant.parse("2026-10-17T00:00:00Z");

	static final URI A = URI.create("https://a.test/search?q=1");

	static final URI B = URI.create("https://b.test/search?q=1");

	static final List<EgressLane> LANES = EgressLane.list("10.0.0.1:6811,10.0.0.2:6699", "webshare");

	/** An answer that takes this long counts like no answer (user decision 2026-10-18) */
	static final Duration SLOW_ANSWER = Duration.ofSeconds(3);

	MutableClock clock = new MutableClock(T0);

	/** host -> lane id -> how long its answers take (the clock moves on while the site answers) */
	final Map<String, Map<String, Duration>> answerTime = new ConcurrentHashMap<>();

	/** host -> the lane ids whose requests to that host are refused with 403 */
	final Map<String, List<String>> refusing = new ConcurrentHashMap<>();

	/** lane ids in request order, per host */
	final Map<String, List<String>> sent = new ConcurrentHashMap<>();

	final List<String> retestLanes = new CopyOnWriteArrayList<>();

	SiteFetcher site = (uri, deadline, lane) -> {
		sent.computeIfAbsent(uri.getHost(), h -> new CopyOnWriteArrayList<>()).add(lane.id());
		clock.advance(answerTime.getOrDefault(uri.getHost(), Map.of()).getOrDefault(lane.id(), Duration.ZERO));
		boolean refused = refusing.getOrDefault(uri.getHost(), List.of()).contains(lane.id());
		return new SiteResponse(refused ? 403 : 200, "text/html", null, new byte[] { 1 }, lane.id());
	};

	EgressLanes lanes(EgressMode mode) {
		return new EgressLanes(mode, LANES, new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr"),
				new InMemoryLaneThrottle(clock), new InMemoryBlockedLanes(5, Duration.ofMinutes(10), Duration.ofDays(7)),
				SLOW_ANSWER, site, new LaneCounters(LANES), clock);
	}

	@Test
	void requestsTakeTurnsAmongTheLanesAndTheAnswerNamesTheLane() { // 17 AC-8
		EgressLanes lanes = lanes(EgressMode.ALL);
		List<String> vias = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			vias.add(lanes.fetch(A, Instant.MAX).via());
		}

		// the site's first lane comes from its name; after that the three lanes take turns in order
		List<String> cycle = List.of("direct", "proxy-01", "proxy-02");
		int first = cycle.indexOf(vias.get(0));
		assertThat(first).isNotNegative();
		for (int i = 0; i < vias.size(); i++) {
			assertThat(vias.get(i)).isEqualTo(cycle.get((first + i) % cycle.size()));
		}
		assertThat(sent.get("a.test")).isEqualTo(vias);
	}

	@Test
	void aLaneBlockedByOneHostPausesThereOnlyAndNoUserRequestGoesThroughIt(CapturedOutput output) { // AC-9
		EgressLanes lanes = lanes(EgressMode.ALL);
		refusing.put("a.test", List.of("proxy-01"));
		int refusedAnswers = 0;
		for (int i = 0; i < 30; i++) {
			if (lanes.fetch(A, Instant.MAX).status() == 403) {
				refusedAnswers++;
			}
		}

		// 5 refusals pause proxy-01 on a.test; from then on a.test alternates between the other two lanes
		assertThat(refusedAnswers).isEqualTo(5);
		assertThat(lanes.blocked().isPaused("proxy-01", "a.test", clock.instant())).isTrue();
		assertThat(sent.get("a.test").subList(15, 30)).doesNotContain("proxy-01").contains("direct", "proxy-02");
		assertThat(output.getOut()).contains("lane_paused host=a.test lane=proxy-01 vendor=webshare")
			.doesNotContain("10.0.0.1");

		// other hosts still use proxy-01
		for (int i = 0; i < 3; i++) {
			lanes.fetch(B, Instant.MAX);
		}
		assertThat(sent.get("b.test")).contains("proxy-01");
		assertThat(lanes.blocked().isPaused("proxy-01", "b.test", clock.instant())).isFalse();
	}

	// user decision 2026-10-18: a lane that answers a host slowly is paused there like one that does not answer
	@Test
	void slowAnswersPauseTheLaneOnThatHostAndAnswersUnderTheLimitDoNot(CapturedOutput output) {
		EgressLanes lanes = lanes(EgressMode.PROXY_ONLY);
		answerTime.put("a.test", Map.of("proxy-01", SLOW_ANSWER.plusMillis(500), "proxy-02", SLOW_ANSWER.minusMillis(500)));
		for (int i = 0; i < 30; i++) {
			assertThat(lanes.fetch(A, Instant.MAX).status()).isEqualTo(200);
		}

		assertThat(lanes.blocked().isPaused("proxy-01", "a.test", clock.instant())).isTrue();
		assertThat(lanes.blocked().isPaused("proxy-02", "a.test", clock.instant())).isFalse();
		assertThat(sent.get("a.test").stream().filter("proxy-01"::equals).count()).isEqualTo(5);
		assertThat(output.getOut()).contains("lane_paused host=a.test lane=proxy-01 vendor=webshare");
	}

	@Test
	void everyLanePausedOnAHostFailsAtOnceWithoutWaiting() {
		EgressLanes lanes = lanes(EgressMode.ALL);
		refusing.put("a.test", List.of("direct", "proxy-01", "proxy-02"));
		for (int i = 0; i < 15; i++) {
			lanes.fetch(A, Instant.MAX);
		}
		int before = sent.get("a.test").size();

		assertThatThrownBy(() -> lanes.fetch(A, clock.instant().plusSeconds(8))).isInstanceOf(UpstreamException.class)
			.satisfies(ex -> {
				assertThat(((UpstreamException) ex).kind()).isEqualTo(UpstreamException.Kind.ERROR);
				assertThat(((UpstreamException) ex).code()).isEqualTo(UpstreamException.NO_LANE_FOR_HOST);
			});
		assertThat(sent.get("a.test")).hasSize(before);
		assertThat(lanes.fetch(B, Instant.MAX).status()).isEqualTo(200);
	}

	@Test
	void retestsRunOnTheLanesOwnSlotTenMinutesThenDoublingUntilASuccess(CapturedOutput output) {
		EgressLanes lanes = lanes(EgressMode.ALL);
		BlockedLaneRetest retest = new BlockedLaneRetest(lanes, Duration.ZERO, Duration.ofSeconds(20));
		refusing.put("a.test", List.of("proxy-01"));
		for (int i = 0; i < 15; i++) {
			lanes.fetch(A, Instant.MAX);
		}
		int userRequestsOnProxy1 = (int) sent.get("a.test").stream().filter("proxy-01"::equals).count();
		assertThat(userRequestsOnProxy1).isEqualTo(5);

		Duration pause = Duration.ofMinutes(10);
		for (int round = 0; round < 3; round++) {
			clock.advance(pause.minusSeconds(1));
			retest.retestDueNow();
			assertThat(sent.get("a.test").stream().filter("proxy-01"::equals).count()).as("not due yet")
				.isEqualTo(5 + round);
			clock.advance(Duration.ofSeconds(1));
			retest.retestDueNow();
			assertThat(sent.get("a.test").stream().filter("proxy-01"::equals).count()).as("one retest")
				.isEqualTo(6 + round);
			assertThat(lanes.blocked().isPaused("proxy-01", "a.test", clock.instant())).isTrue();
			pause = pause.multipliedBy(2);
		}
		assertThat(output.getOut()).contains("lane_retest host=a.test lane=proxy-01 vendor=webshare result=blocked pause=PT20M")
			.contains("result=blocked pause=PT40M")
			.contains("result=blocked pause=PT1H20M");

		// users keep using the other lanes meanwhile: still only the retests went through proxy-01
		for (int i = 0; i < 10; i++) {
			lanes.fetch(A, Instant.MAX);
		}
		assertThat(sent.get("a.test").stream().filter("proxy-01"::equals).count()).isEqualTo(8);

		// the site opens again: the next retest resumes the lane and users use it again
		refusing.remove("a.test");
		clock.advance(pause);
		retest.retestDueNow();
		assertThat(lanes.blocked().isPaused("proxy-01", "a.test", clock.instant())).isFalse();
		assertThat(output.getOut()).contains("lane_resumed host=a.test lane=proxy-01 vendor=webshare");
		for (int i = 0; i < 3; i++) {
			lanes.fetch(A, Instant.MAX);
		}
		assertThat(sent.get("a.test").subList(sent.get("a.test").size() - 3, sent.get("a.test").size()))
			.contains("proxy-01");
		retest.close();
	}

	@Test
	void proxyOnlyNeverUsesTheDirectLaneAndDoesNotFallBackToIt() { // from-cycle-20-proxy-only.md
		EgressLanes lanes = lanes(EgressMode.PROXY_ONLY);
		BlockedLaneRetest retest = new BlockedLaneRetest(lanes, Duration.ZERO, Duration.ofSeconds(20));
		assertThat(lanes.lanes()).extracting(EgressLane::id).containsExactly("proxy-01", "proxy-02");
		refusing.put("a.test", List.of("proxy-01", "proxy-02"));
		for (int i = 0; i < 10; i++) {
			lanes.fetch(A, Instant.MAX);
		}

		assertThatThrownBy(() -> lanes.fetch(A, Instant.MAX))
			.satisfies(ex -> assertThat(((UpstreamException) ex).code()).isEqualTo(UpstreamException.NO_LANE_FOR_HOST));
		clock.advance(Duration.ofMinutes(10));
		retest.retestDueNow();
		assertThat(sent.get("a.test")).doesNotContain("direct").hasSize(12);
		retest.close();
	}

	@Test
	void directOnlySendsNothingThroughAProxy() {
		EgressLanes lanes = lanes(EgressMode.DIRECT_ONLY);
		for (int i = 0; i < 5; i++) {
			lanes.fetch(A, Instant.MAX);
		}

		assertThat(sent.get("a.test")).containsOnly("direct");
	}

	@Test
	void statusPortReadsTheModeAndThePausedPairs() { // AC-27 server part
		EgressLanes lanes = lanes(EgressMode.PROXY_ONLY);
		DefaultEgressLaneStatus status = new DefaultEgressLaneStatus(lanes.mode(), lanes.blocked(), clock);
		assertThat(status.egressMode()).isEqualTo(com.chaekcheck.availability.internal.status.EgressLaneStatus.Mode.PROXY_ONLY);
		assertThat(status.vendorLanesPaused()).isFalse();
		assertThat(status.pausedLaneCount()).isZero();

		refusing.put("a.test", List.of("proxy-01", "proxy-02"));
		for (int i = 0; i < 10; i++) {
			lanes.fetch(A, Instant.MAX);
		}
		assertThat(status.pausedLaneCount()).isEqualTo(2);
		assertThat(status.vendorLanesPaused()).as("every lane paused on one site is not a vendor pause").isFalse();
	}

	@Test
	void oneTimeoutOrBodyTooLargeDoesNotPauseALane() {
		EgressLanes lanes = new EgressLanes(EgressMode.ALL, List.of(EgressLane.DIRECT),
				new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr"), new InMemoryLaneThrottle(clock),
				new InMemoryBlockedLanes(5, Duration.ofMinutes(10), Duration.ofDays(7)), SLOW_ANSWER, failing(uri -> {
					if (uri.getPath().endsWith("big")) {
						throw UpstreamException.bodyTooLarge(uri, 1, 2, null);
					}
					if (uri.getPath().endsWith("busy")) {
						throw UpstreamException.parseBusy("no slot");
					}
					throw UpstreamException.timeout("read timed out");
				}), new LaneCounters(List.of(EgressLane.DIRECT)), clock);

		for (String path : List.of("/big", "/big", "/big", "/big", "/big", "/busy", "/busy", "/busy", "/busy", "/busy",
				"/slow", "/slow", "/slow", "/slow")) {
			assertThatThrownBy(() -> lanes.fetch(URI.create("https://a.test" + path), Instant.MAX));
		}
		assertThat(lanes.blocked().isPaused("direct", "a.test", clock.instant())).isFalse();
		assertThatThrownBy(() -> lanes.fetch(URI.create("https://a.test/slow"), Instant.MAX));
		assertThat(lanes.blocked().isPaused("direct", "a.test", clock.instant())).as("five timeouts in a row")
			.isTrue();
	}

	private static SiteFetcher failing(Function<URI, SiteResponse> f) {
		return (uri, deadline, lane) -> f.apply(uri);
	}

	/** A clock the test moves by hand. */
	static final class MutableClock extends Clock {

		private Instant now;

		MutableClock(Instant start) {
			this.now = start;
		}

		void advance(Duration d) {
			now = now.plus(d);
		}

		@Override
		public ZoneOffset getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(java.time.ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
