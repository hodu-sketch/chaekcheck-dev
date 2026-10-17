package com.chaekcheck.availability.internal.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.fetch.HostQueueBoard;
import com.chaekcheck.availability.internal.fetch.InMemoryHostQueueBoard;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;
import com.chaekcheck.availability.internal.status.EgressLaneStatus;

/** AC-40: one line with every field, and the minute's counts start again at zero after it. */
@ExtendWith(OutputCaptureExtension.class)
class LookupOpsSummaryLogTests {

	static final Instant T0 = Instant.parse("2026-10-20T03:00:00Z");

	@Test
	void oneLineWithEveryFieldAndTheCountsResetAfterIt(CapturedOutput output) {
		LookupOpsCounters counters = new LookupOpsCounters();
		SearchSlots slots = new SearchSlots(40, new InMemoryLastTimeRecord(), counters,
				Clock.fixed(T0, ZoneOffset.UTC));
		ParseSlots parseSlots = new ParseSlots(8, Duration.ofSeconds(2));
		InMemoryHostQueueBoard board = new InMemoryHostQueueBoard();
		SearchSlots.Slot a = slots.acquire();
		SearchSlots.Slot b = slots.acquire();
		a.close();
		counters.libraryRequest();
		counters.libraryRequest();
		counters.bodyTooLarge();
		counters.dbError();
		counters.refused();
		parseSlots.parse(() -> "x");
		board.begin(HostQueueBoard.DEFAULT_LANE, "portal.test", 1215, T0);
		try (LookupOpsSummaryLog summary = new LookupOpsSummaryLog(counters, slots, parseSlots, board,
				EgressLaneStatus.NONE, AdapterFixtures.LOOKUP_STATUS, Duration.ofHours(1),
				Clock.fixed(T0, ZoneOffset.UTC))) {

			summary.logNow();
			assertThat(output.getOut()).containsPattern("lookup ops: slotsMax=40 slotsInUse=1 slotsMaxInUse=2 refused=1 "
					+ "libraryRequests=2 bodyTooLarge=1 dbErrors=1 parseMax=8 parseInUse=0 parseMaxInUse=1 "
					+ "queuedLibraries=1 pausedLanes=0 heapUsedMb=\\d+ heapMaxMb=-?\\d+");

			summary.logNow();
			assertThat(output.getOut()).containsPattern("lookup ops: slotsMax=40 slotsInUse=1 slotsMaxInUse=1 refused=0 "
					+ "libraryRequests=0 bodyTooLarge=0 dbErrors=0 parseMax=8 parseInUse=0 parseMaxInUse=0 ");
		}
		b.close();
	}

}
