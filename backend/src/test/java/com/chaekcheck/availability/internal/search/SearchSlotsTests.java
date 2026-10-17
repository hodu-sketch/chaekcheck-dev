package com.chaekcheck.availability.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.state.InMemoryLastTimeRecord;

/** Cycle 20 (ADR-0118): a refusal writes its time into the record, in-use feeds the status levels. */
class SearchSlotsTests {

	static final Instant AT = Instant.parse("2026-10-20T03:00:00Z");

	InMemoryLastTimeRecord refusals = new InMemoryLastTimeRecord();

	LookupOpsCounters counters = new LookupOpsCounters();

	SearchSlots slots = new SearchSlots(2, refusals, counters, Clock.fixed(AT, ZoneOffset.UTC));

	@Test
	void refusalIsRecordedWithItsTimeAndCounted() {
		SearchSlots.Slot first = slots.acquire();
		SearchSlots.Slot second = slots.acquire();
		assertThat(slots.inUse()).isEqualTo(2);
		assertThat(refusals.last()).isEmpty();

		assertThatThrownBy(slots::acquire).isInstanceOf(SearchBusyException.class);

		assertThat(refusals.last()).contains(AT);
		assertThat(refusals.within(Duration.ofSeconds(10), AT.plusSeconds(10))).isTrue();
		assertThat(refusals.within(Duration.ofSeconds(10), AT.plusSeconds(11))).isFalse();
		first.close();
		second.close();
		second.close();
		assertThat(slots.inUse()).isZero();
		LookupOpsCounters.Snapshot counts = counters.drain();
		assertThat(counts.refusals()).isEqualTo(1);
		assertThat(counts.maxSlotsInUse()).isEqualTo(2);
	}

}
