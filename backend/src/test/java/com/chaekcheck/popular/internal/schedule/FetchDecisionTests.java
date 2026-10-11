package com.chaekcheck.popular.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.chaekcheck.popular.internal.store.PopularStore;

/** {@code shouldFetch} on fixed dates, Asia/Seoul (plan cycle 10 step 4, design #7 to #9, AC-11). */
class FetchDecisionTests {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	static final YearMonth AUG = YearMonth.of(2026, 8);

	static final YearMonth SEP = YearMonth.of(2026, 9);

	final PopularStore store = mock(PopularStore.class);

	@Test
	void nothingStoredFetchesEvenOnThe1st() {
		given(store.latestMonth()).willReturn(Optional.empty());

		assertThat(decide("2026-10-01T05:00")).contains(SEP);
		// fails keep going: still fetched every day while nothing is stored (spec R1-6)
		given(store.failuresSince(any(), any())).willReturn(5);
		assertThat(decide("2026-10-07T05:00")).contains(SEP);
	}

	@Test
	void the1stAnd2ndWithAnEarlierMonthDoNotFetch() {
		given(store.latestMonth()).willReturn(Optional.of(AUG));
		given(store.fetchedAt(SEP)).willReturn(Optional.empty());

		assertThat(decide("2026-10-01T05:00")).isEmpty();
		assertThat(decide("2026-10-02T23:59")).isEmpty();
	}

	@Test
	void the3rdWithoutTheTargetFetches() {
		given(store.latestMonth()).willReturn(Optional.of(AUG));
		given(store.fetchedAt(SEP)).willReturn(Optional.empty());

		assertThat(decide("2026-10-03T00:00")).contains(SEP);
		assertThat(decide("2026-10-03T05:00")).contains(SEP);
	}

	@Test
	void oneFailureOnThe3rdFetchesOnThe4thTwoDoNot() {
		given(store.latestMonth()).willReturn(Optional.of(AUG));
		given(store.fetchedAt(SEP)).willReturn(Optional.empty());
		Instant third = seoul("2026-10-03T00:00");
		given(store.failuresSince(eq(SEP), eq(third))).willReturn(1);

		assertThat(decide("2026-10-04T05:00")).contains(SEP);

		given(store.failuresSince(eq(SEP), eq(third))).willReturn(2);
		assertThat(decide("2026-10-05T05:00")).isEmpty();
		assertThat(decide("2026-10-20T05:00")).isEmpty();
	}

	@Test
	void storedOnThe2ndIsFetchedAgainOnThe3rd() {
		given(store.latestMonth()).willReturn(Optional.of(SEP));
		given(store.fetchedAt(SEP)).willReturn(Optional.of(seoul("2026-10-02T05:01")));

		assertThat(decide("2026-10-02T23:00")).isEmpty();
		assertThat(decide("2026-10-03T05:00")).contains(SEP);
	}

	@Test
	void storedOnOrAfterThe3rdIsNotFetchedAgain() {
		given(store.latestMonth()).willReturn(Optional.of(SEP));
		given(store.fetchedAt(SEP)).willReturn(Optional.of(seoul("2026-10-03T00:00")));

		assertThat(decide("2026-10-03T05:00")).isEmpty();
		assertThat(decide("2026-10-31T05:00")).isEmpty();
	}

	@Test
	void januaryTargetsDecember() {
		given(store.latestMonth()).willReturn(Optional.of(YearMonth.of(2026, 11)));
		given(store.fetchedAt(YearMonth.of(2026, 12))).willReturn(Optional.empty());

		assertThat(decide("2027-01-03T05:00")).contains(YearMonth.of(2026, 12));
		// 2027-01-02 23:30 KST is still the 2nd in Seoul although the 2nd in UTC is long over
		assertThat(decide("2027-01-02T23:30")).isEmpty();
	}

	private java.util.Optional<YearMonth> decide(String seoulTime) {
		Clock clock = Clock.fixed(seoul(seoulTime), ZoneId.of("UTC"));
		return new FetchDecision(store, clock, SEOUL).monthToFetch();
	}

	private static Instant seoul(String localTime) {
		return LocalDateTime.parse(localTime).atZone(SEOUL).toInstant();
	}

}
