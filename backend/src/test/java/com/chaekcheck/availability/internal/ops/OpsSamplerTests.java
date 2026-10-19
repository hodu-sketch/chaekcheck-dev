package com.chaekcheck.availability.internal.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.MemoryUsage;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.egress.DefaultEgressLaneStatus;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.chaekcheck.availability.internal.egress.EgressMode;
import com.chaekcheck.availability.internal.egress.InMemoryBlockedLanes;
import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;

/** The 10-second DB sample (user decision 2026-10-19): row values, its own window, DB errors never thrown. */
@ExtendWith(OutputCaptureExtension.class)
class OpsSamplerTests {

	static final Instant T0 = Instant.parse("2026-10-20T03:00:00Z");

	final MovableClock clock = new MovableClock(T0);

	final LookupOpsCounters counters = new LookupOpsCounters();

	final SearchSlots slots = new SearchSlots(40, new InMemoryLastTimeRecord(), counters, clock);

	final ParseSlots parseSlots = new ParseSlots(8, Duration.ofSeconds(2));

	final InMemoryHostQueueBoard board = new InMemoryHostQueueBoard();

	final LaneCounters laneCounters = new LaneCounters(
			List.of(EgressLane.DIRECT, EgressLane.proxy(1, "webshare", "p", 1)));

	final InMemoryBlockedLanes blocked = new InMemoryBlockedLanes(1, Duration.ofMinutes(10), Duration.ofDays(7));

	final FakeStore store = new FakeStore();

	final OpsSampler sampler = new OpsSampler(counters, slots, parseSlots, board,
			new DefaultEgressLaneStatus(EgressMode.PROXY_ONLY, blocked, clock), laneCounters,
			AdapterFixtures.LOOKUP_STATUS, store, Duration.ofSeconds(10), Duration.ofDays(90), clock,
			() -> new MemoryUsage(0, 210L << 20, 300L << 20, 512L << 20));

	@Test
	void oneRowWithTheWindowsNumbersThenTheWindowStartsAtZero() {
		SearchSlots.Slot a = slots.acquire();
		SearchSlots.Slot b = slots.acquire();
		a.close();
		counters.libraryRequest();
		counters.libraryRequest();
		counters.libraryRequest();
		counters.bodyTooLarge();
		counters.dbError();
		counters.refused();
		parseSlots.parse(() -> "x");
		board.begin(HostQueueBoard.DEFAULT_LANE, "portal.test", 1215, T0);
		laneCounters.request("direct");
		laneCounters.request("proxy-01");
		laneCounters.pauseStarted();
		laneCounters.retestSent();
		blocked.requestEnded("proxy-01", "blocked.test", URI.create("https://blocked.test/"), true, T0);
		clock.advance(Duration.ofSeconds(10));

		sampler.sampleNow();

		assertThat(store.inserted).containsExactly(new OpsSample(T0.plusSeconds(10), 10_000, 40, 1, 2, 1, 3, 1, 1,
				8, 0, 1, 1, 1, 210, 512, "PROXY_ONLY", 2, 1, 1, 0));

		clock.advance(Duration.ofSeconds(10));
		sampler.sampleNow();
		assertThat(store.inserted.get(1)).isEqualTo(new OpsSample(T0.plusSeconds(20), 10_000, 40, 1, 1, 0, 0, 0, 0,
				8, 0, 0, 0, 1, 210, 512, "PROXY_ONLY", 0, 0, 0, 0));
		b.close();
	}

	@Test
	void theMinuteLinesCountsAreLeftAlone() {
		counters.libraryRequest();
		counters.refused();
		laneCounters.request("direct");
		parseSlots.parse(() -> "x");

		sampler.sampleNow();

		assertThat(counters.drain()).isEqualTo(new LookupOpsCounters.Snapshot(1, 0, 1, 0, 0));
		assertThat(laneCounters.drain().requestsPerLane()).isEqualTo("direct:1;proxy-01:0");
		assertThat(parseSlots.drainMaxInUse()).isEqualTo(1);
		// and the other way round: the minute line's drain left the next sample's counts
		counters.libraryRequest();
		counters.drain();
		sampler.sampleNow();
		assertThat(store.inserted.get(1).libraryRequests()).isEqualTo(1);
	}

	@Test
	void noHeapLimitIsNull() {
		OpsSampler noLimit = new OpsSampler(counters, slots, parseSlots, board,
				new DefaultEgressLaneStatus(EgressMode.ALL, blocked, clock), laneCounters, AdapterFixtures.LOOKUP_STATUS,
				store, Duration.ofSeconds(10), Duration.ofDays(90), clock, () -> new MemoryUsage(0, 1L << 20, 1L << 20, -1));
		noLimit.sampleNow();
		assertThat(store.inserted.getFirst().heapMaxMb()).isNull();
		assertThat(store.inserted.getFirst().egressMode()).isEqualTo("ALL");
	}

	@Test
	void aDbErrorIsWarnedOnceAMinuteAndNeverThrown(CapturedOutput output) {
		store.failure.set(new IllegalStateException("db down"));
		sampler.sampleNow();
		clock.advance(Duration.ofSeconds(10));
		sampler.sampleNow();
		clock.advance(Duration.ofSeconds(10));
		sampler.sampleNow();
		assertThat(warnings(output)).isEqualTo(1);
		assertThat(output.getOut()).contains("lookup sample not written (1 failures since the last warning)");

		clock.advance(Duration.ofSeconds(45));
		sampler.sampleNow();
		assertThat(warnings(output)).isEqualTo(2);
		assertThat(output.getOut()).contains("lookup sample not written (3 failures since the last warning)");

		store.failure.set(null);
		clock.advance(Duration.ofSeconds(10));
		sampler.sampleNow();
		assertThat(store.inserted).hasSize(1);
	}

	@Test
	void maintainKeepsTwoMonthsAheadAndDropsPastTheRetention(CapturedOutput output) {
		sampler.maintainNow();
		assertThat(store.ensured).containsExactly(2);
		assertThat(store.droppedBefore).containsExactly(T0.minus(Duration.ofDays(90)));

		store.failure.set(new IllegalStateException("db down"));
		sampler.maintainNow();
		assertThat(output.getOut()).contains("lookup sample partitions not kept");
	}

	private static long warnings(CapturedOutput output) {
		return output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("lookup sample")).count();
	}

	static final class FakeStore implements OpsSampleStore {

		final List<OpsSample> inserted = new ArrayList<>();

		final List<Integer> ensured = new ArrayList<>();

		final List<Instant> droppedBefore = new ArrayList<>();

		final AtomicReference<RuntimeException> failure = new AtomicReference<>();

		@Override
		public void insert(OpsSample sample) {
			fail();
			inserted.add(sample);
		}

		@Override
		public int ensurePartitions(int monthsAhead) {
			fail();
			ensured.add(monthsAhead);
			return 0;
		}

		@Override
		public int dropBefore(Instant cutoff) {
			fail();
			droppedBefore.add(cutoff);
			return 0;
		}

		private void fail() {
			RuntimeException ex = failure.get();
			if (ex != null) {
				throw ex;
			}
		}

	}

	static final class MovableClock extends Clock {

		private Instant now;

		MovableClock(Instant now) {
			this.now = now;
		}

		void advance(Duration duration) {
			now = now.plus(duration);
		}

		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return now;
		}

	}

}
