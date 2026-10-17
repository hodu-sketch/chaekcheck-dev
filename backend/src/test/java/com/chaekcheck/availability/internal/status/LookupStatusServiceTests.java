package com.chaekcheck.availability.internal.status;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;
import com.chaekcheck.availability.internal.status.LookupStatus.Level;
import com.chaekcheck.availability.internal.status.LookupStatus.Reason;

/** The judgement table of spec R1 (AC-2, AC-27 server part, AC-37 30s part) and the 1s memo, with a fake clock. */
class LookupStatusServiceTests {

	static final Instant T0 = Instant.parse("2026-10-20T03:00:00Z");

	InMemoryLastTimeRecord refusals = new InMemoryLastTimeRecord();

	InMemoryLastTimeRecord dbErrors = new InMemoryLastTimeRecord();

	InMemoryHostQueueBoard board = new InMemoryHostQueueBoard();

	LookupOpsCounters counters = new LookupOpsCounters();

	SearchSlots slots = new SearchSlots(40, refusals, counters, Clock.fixed(T0, ZoneOffset.UTC));

	FakeLanes lanes = new FakeLanes();

	MutableClock clock = new MutableClock(T0);

	LookupStatusService service = new LookupStatusService(slots, refusals, dbErrors, board, lanes,
			AdapterFixtures.LOOKUP_STATUS, clock);

	@Test
	void greenUnderHalfYellowFromHalfRedWhenFull() { // R1 with 40 slots: 19 green, 20 yellow, 40 red
		List<SearchSlots.Slot> taken = new ArrayList<>();
		take(taken, 19);
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason, LookupStatus::inUse,
				LookupStatus::slots).containsExactly(Level.GREEN, Reason.NONE, 19, 40);

		take(taken, 1);
		assertThat(service.compute(T0).level()).isEqualTo(Level.YELLOW);

		take(taken, 20);
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason, LookupStatus::inUse)
			.containsExactly(Level.RED, Reason.NONE, 40);
		taken.forEach(SearchSlots.Slot::close);
		assertThat(service.compute(T0).level()).isEqualTo(Level.GREEN);
	}

	@Test
	void redForTenSecondsAfterARefusalThenBackToTheRule() { // AC-3 server part
		refusals.mark(T0);

		assertThat(service.compute(T0.plusSeconds(10)).level()).isEqualTo(Level.RED);
		assertThat(service.compute(T0.plusSeconds(11)).level()).isEqualTo(Level.GREEN);
	}

	@Test
	void dbErrorIsGrayForThirtySecondsAndComesBeforeRedAndNoLane() { // overall review #9, design review #4, AC-37
		dbErrors.mark(T0);
		refusals.mark(T0);
		lanes.mode = EgressLaneStatus.Mode.PROXY_ONLY;
		lanes.paused = true;

		assertThat(service.compute(T0.plusSeconds(30))).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.GRAY, Reason.DB_ERROR);
		assertThat(service.compute(T0.plusSeconds(31))).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.RED, Reason.NO_LANE);
	}

	@Test
	void vendorLanesPausedIsAtLeastYellowAndProxyOnlyMakesItRedNoLane() { // AC-27 server part
		lanes.paused = true;
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.YELLOW, Reason.VENDOR_PAUSED);

		List<SearchSlots.Slot> taken = new ArrayList<>();
		take(taken, 40);
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.RED, Reason.NONE);
		taken.forEach(SearchSlots.Slot::close);

		lanes.mode = EgressLaneStatus.Mode.PROXY_ONLY;
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.RED, Reason.NO_LANE);

		lanes.mode = EgressLaneStatus.Mode.DIRECT_ONLY;
		assertThat(service.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.YELLOW, Reason.VENDOR_PAUSED);
	}

	@Test
	void withoutLaneCodeTheReasonIsNone() { // cycle 17 not in yet: EgressLaneStatus.NONE
		LookupStatusService noLanes = new LookupStatusService(slots, refusals, dbErrors, board, EgressLaneStatus.NONE,
				AdapterFixtures.LOOKUP_STATUS, clock);

		assertThat(noLanes.compute(T0)).extracting(LookupStatus::level, LookupStatus::reason)
			.containsExactly(Level.GREEN, Reason.NONE);
	}

	@Test
	void queuedLibrariesComeFromTheBoardWithTheWindows() { // R4, AC-5 server part
		board.begin(HostQueueBoard.DEFAULT_LANE, "portal.test", 1215, T0);
		board.begin(HostQueueBoard.DEFAULT_LANE, "portal.test", 3301, T0);

		assertThat(service.compute(T0.plusSeconds(5)).queuedLibraryIds()).containsExactly(1215L, 3301L);
		assertThat(service.compute(T0.plusSeconds(11)).queuedLibraryIds()).isEmpty();
		assertThat(service.compute(T0).serverTime()).isEqualTo(T0);
	}

	@Test
	void twoCallsWithinASecondComputeOnce() { // memo 1s [확정]
		AtomicInteger computed = new AtomicInteger();
		LookupStatusService counting = new LookupStatusService(slots, refusals, dbErrors, board, lanes,
				AdapterFixtures.LOOKUP_STATUS, clock) {
			@Override
			LookupStatus compute(Instant now) {
				computed.incrementAndGet();
				return super.compute(now);
			}
		};

		LookupStatus first = counting.current();
		clock.now = T0.plusMillis(900);
		LookupStatus second = counting.current();
		assertThat(second).isSameAs(first);
		assertThat(computed.get()).isEqualTo(1);

		clock.now = T0.plusMillis(1000);
		assertThat(counting.current()).isNotSameAs(first);
		assertThat(computed.get()).isEqualTo(2);
	}

	private void take(List<SearchSlots.Slot> taken, int count) {
		for (int i = 0; i < count; i++) {
			taken.add(slots.acquire());
		}
	}

	static final class FakeLanes implements EgressLaneStatus {

		Mode mode = Mode.ALL;

		boolean paused;

		@Override
		public Mode egressMode() {
			return mode;
		}

		@Override
		public boolean vendorLanesPaused() {
			return paused;
		}

	}

	static final class MutableClock extends Clock {

		Instant now;

		MutableClock(Instant now) {
			this.now = now;
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
