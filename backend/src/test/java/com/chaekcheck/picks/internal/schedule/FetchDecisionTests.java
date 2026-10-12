package com.chaekcheck.picks.internal.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.chaekcheck.picks.internal.store.PicksStore;

/** The fetch decision on fixed dates, Asia/Seoul (plan cycle 14 step 3; ADR-0092, AC-13, AC-13b). */
class FetchDecisionTests {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	/** 2026-10-12 is a Monday. */
	static final String MONDAY = "2026-10-12";

	final PicksStore store = mock(PicksStore.class);

	@Test
	void nothingStoredFetchesAtStartUpAndAt5() {
		given(store.hasPicks()).willReturn(false);

		assertThat(decide("2026-10-14T09:30", true)).contains(FetchTrigger.STARTUP);
		// Wednesday 05:00
		assertThat(decide("2026-10-14T05:00", false)).contains(FetchTrigger.CATCH_UP);
	}

	@Test
	void mondayAt5Fetches() {
		stored("2026-10-05T05:00:41", "SUCCEEDED");

		assertThat(decide(MONDAY + "T05:00", false)).contains(FetchTrigger.SCHEDULED);
	}

	@Test
	void mondayFailureTriesAgainEveryDayUntilASuccess() {
		stored("2026-10-05T05:00:41", "FAILED");

		assertThat(decide("2026-10-13T05:00", false)).contains(FetchTrigger.RETRY);
		assertThat(decide("2026-10-14T05:00", false)).contains(FetchTrigger.RETRY);
	}

	@Test
	void mondaySuccessFetchesNothingOnTuesday() {
		stored(MONDAY + "T05:00:41", "SUCCEEDED");

		assertThat(decide("2026-10-13T05:00", false)).isEmpty();
		assertThat(decide("2026-10-13T11:00", true)).isEmpty();
	}

	@Test
	void aSuccessTodayFetchesNothingMore() {
		stored(MONDAY + "T03:10:00", "SUCCEEDED");

		assertThat(decide(MONDAY + "T05:00", false)).isEmpty();
	}

	@Test
	void startUpAfterMoreThanSevenDaysCatchesUp() { // AC-13b
		stored("2026-10-05T05:00:41", "SUCCEEDED");

		assertThat(decide("2026-10-13T09:00", true)).contains(FetchTrigger.CATCH_UP);
		// the same at 05:00 on a day that is not Monday
		assertThat(decide("2026-10-13T05:00", false)).contains(FetchTrigger.CATCH_UP);
	}

	@Test
	void startUpWithinSevenDaysDoesNotFetch() {
		stored("2026-10-06T05:00:41", "SUCCEEDED");

		assertThat(decide("2026-10-12T04:00", true)).isEmpty();
	}

	@Test
	void exactlySevenDaysIsNotOlderThanSeven() {
		stored("2026-10-06T09:00:00", "SUCCEEDED");

		assertThat(decide("2026-10-13T09:00", true)).isEmpty();
		assertThat(decide("2026-10-13T09:00:01", true)).contains(FetchTrigger.CATCH_UP);
	}

	@Test
	void aFailureIsNotTriedAgainAtStartUp() {
		stored("2026-10-08T05:00:41", "FAILED");
		given(store.lastSucceededAt()).willReturn(Optional.of(at("2026-10-07T05:00:41")));

		assertThat(decide("2026-10-09T12:00", true)).isEmpty();
	}

	private void stored(String lastRun, String status) {
		given(store.hasPicks()).willReturn(true);
		given(store.lastStatus()).willReturn(Optional.of(status));
		given(store.lastSucceededAt()).willReturn(status.equals("SUCCEEDED") ? Optional.of(at(lastRun))
				: Optional.of(at(lastRun).minusSeconds(86_400 * 3)));
	}

	private Optional<FetchTrigger> decide(String now, boolean atStartup) {
		return new FetchDecision(store, Clock.fixed(at(now), SEOUL), SEOUL).decide(atStartup);
	}

	private static Instant at(String local) {
		return LocalDateTime.parse(local).atZone(SEOUL).toInstant();
	}

}
