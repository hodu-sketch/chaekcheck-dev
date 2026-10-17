package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.LaneThrottle;
import com.chaekcheck.availability.internal.egress.HostRules;
import com.chaekcheck.availability.internal.egress.EgressMode;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.chaekcheck.availability.internal.egress.TestLanes;
import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.adapter.KyoboV1Adapter;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * The host queue board (cycle 20, overall review #3, AC-32): the two unit tests the handoff names (an error while
 * waiting leaves no wait behind; a wait older than 10s is not listed) plus the 60s timeout window and the portal
 * rule (every library seen on a timed-out host).
 */
class HostQueueBoardTests {

	static final Instant T0 = Instant.parse("2026-10-20T03:00:00Z");

	static final Duration WAIT_WINDOW = Duration.ofSeconds(10);

	static final Duration TIMEOUT_WINDOW = Duration.ofSeconds(60);

	static final String LANE = HostQueueBoard.DEFAULT_LANE;

	InMemoryHostQueueBoard board = new InMemoryHostQueueBoard();

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void waitOlderThanTheWindowIsNotListedAnyMore() { // AC-32 (2): a lost end heals itself
		board.begin(LANE, "lib.test", 7, T0);

		assertThat(queued(T0.plusSeconds(9))).containsExactly(7L);
		assertThat(queued(T0.plusSeconds(10))).containsExactly(7L);
		assertThat(queued(T0.plusSeconds(11))).isEmpty();
		assertThat(board.waitingCount(T0.plusSeconds(11), WAIT_WINDOW)).isZero();
	}

	@Test
	void endingAWaitTwiceCountsOnceAndEmptiesTheRow() {
		HostQueueBoard.Wait first = board.begin(LANE, "lib.test", 7, T0);
		HostQueueBoard.Wait second = board.begin(LANE, "lib.test", 7, T0);
		assertThat(board.waitingCount(T0, WAIT_WINDOW)).isEqualTo(2);

		first.end();
		first.end();
		assertThat(board.waitingCount(T0, WAIT_WINDOW)).isEqualTo(1);
		assertThat(queued(T0)).containsExactly(7L);

		second.end();
		assertThat(board.waitingCount(T0, WAIT_WINDOW)).isZero();
		assertThat(queued(T0)).isEmpty();
	}

	@Test
	void timeoutKeepsEveryLibrarySeenOnTheHostQueuedForSixtySeconds() { // R4 (나), portal hosts
		board.begin(LANE, "portal.test", 1, T0).end();
		board.begin(LANE, "portal.test", 2, T0).end();
		board.begin(LANE, "other.test", 3, T0).end();
		board.timedOut(LANE, "portal.test", T0.plusSeconds(5));

		assertThat(queued(T0.plusSeconds(6))).containsExactly(1L, 2L);
		assertThat(queued(T0.plusSeconds(65))).containsExactly(1L, 2L);
		assertThat(queued(T0.plusSeconds(66))).isEmpty();
	}

	@Test
	void listIsCappedAtTheLimit() {
		for (long id = 1; id <= 5; id++) {
			board.begin(LANE, "lib" + id + ".test", id, T0);
		}

		assertThat(board.queuedLibraryIds(T0, WAIT_WINDOW, TIMEOUT_WINDOW, 3)).hasSize(3);
	}

	@Test
	void errorWhileWaitingLeavesNoWaitBehind() { // AC-32 (1), through LibraryPageFetcher's wrapping
		LaneThrottle failing = new LaneThrottle() {
			@Override
			public <T> T run(List<EgressLane> lanes, URI uri, HostRules.Rule rule, Instant deadline, LaneCall<T> call) {
				throw UpstreamException.error("throttle broke while waiting");
			}
		};
		LibraryPageFetcher fetcher = fetcher(failing);

		assertThatThrownBy(() -> fetcher.fetch(TestTargets.GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOf(UpstreamException.class);

		assertThat(board.waitingCount(T0, WAIT_WINDOW)).isZero();
		assertThat(queued(T0)).isEmpty();
	}

	@Test
	void throttleTimeoutWhileWaitingIsRecordedForTheHost() {
		LaneThrottle slow = new LaneThrottle() {
			@Override
			public <T> T run(List<EgressLane> lanes, URI uri, HostRules.Rule rule, Instant deadline, LaneCall<T> call) {
				throw UpstreamException.timeout("no free slot before the deadline");
			}
		};
		LibraryPageFetcher fetcher = fetcher(slow);

		assertThatThrownBy(() -> fetcher.fetch(TestTargets.GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOf(UpstreamException.class);

		assertThat(board.waitingCount(T0, WAIT_WINDOW)).isZero();
		assertThat(queued(T0.plusSeconds(60))).containsExactly(TestTargets.GOHEUNG.id());
		assertThat(queued(T0.plusSeconds(61))).isEmpty();
	}

	@Test
	void waitEndsWhenTheCallStartsSoACallThatTimesOutIsNotAQueueTimeout() {
		SiteFetcher site = mock(SiteFetcher.class);
		given(site.fetch(any(), any(), any())).willThrow(UpstreamException.timeout("read timed out"));
		LibraryPageFetcher fetcher = new LibraryPageFetcher(List.of(new KyoboV1Adapter(AdapterFixtures.PROPERTIES)),
				new CaffeineSearchPageCache(Duration.ofMinutes(3), 100, executor, Ticker.systemTicker()),
				TestLanes.direct(site, Clock.systemUTC()), Duration.ofSeconds(5),
				Clock.fixed(T0, ZoneOffset.UTC), new BookKeyNormalizer(), new ParseSlots(8, Duration.ofSeconds(2)),
				board);

		assertThatThrownBy(() -> fetcher.fetch(TestTargets.GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOf(UpstreamException.class);

		assertThat(queued(T0)).isEmpty();
	}

	private LibraryPageFetcher fetcher(LaneThrottle throttle) {
		return new LibraryPageFetcher(List.of(new KyoboV1Adapter(AdapterFixtures.PROPERTIES)),
				new CaffeineSearchPageCache(Duration.ofMinutes(3), 100, executor, Ticker.systemTicker()),
				TestLanes.of(EgressMode.DIRECT_ONLY, List.of(EgressLane.DIRECT), HostRules.standard(), throttle,
						mock(SiteFetcher.class), Clock.fixed(T0, ZoneOffset.UTC)),
				Duration.ofSeconds(5), Clock.fixed(T0, ZoneOffset.UTC),
				new BookKeyNormalizer(), new ParseSlots(8, Duration.ofSeconds(2)), board);
	}

	private java.util.Set<Long> queued(Instant now) {
		return board.queuedLibraryIds(now, WAIT_WINDOW, TIMEOUT_WINDOW, 200);
	}

}
