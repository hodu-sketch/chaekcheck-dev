package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** Cycle 21 handoff step 4-2 (ADR-0125, AC-6, AC-7): held counts, then the first volume, then the smaller id. */
class BookChoiceTests {

	/** 1-5 owned, 11-13 subscription; 99 is disabled (not in the map). */
	static final Map<Long, Boolean> KINDS = Map.of(1L, false, 2L, false, 3L, false, 4L, false, 5L, false, 11L, true,
			12L, true, 13L, true);

	@Test
	void subscriptionLibrariesCountAsOne() {
		assertThat(BookChoice.held(new long[] { 11, 12, 13 }, KINDS)).isEqualTo(1);
		assertThat(BookChoice.held(new long[] { 1, 2, 11, 12, 13 }, KINDS)).isEqualTo(3);
		assertThat(BookChoice.held(new long[0], KINDS)).isZero();
	}

	@Test
	void disabledLibrariesCountForNothing() {
		assertThat(BookChoice.held(new long[] { 1, 99 }, KINDS)).isEqualTo(1);
		assertThat(BookChoice.held(new long[] { 99 }, KINDS)).isZero();
	}

	@Test
	void mostHeldWins() { // AC-6: two owned and three subscription (3) lose to four owned (4)
		BookChoice.Candidate subscriptions = new BookChoice.Candidate(1, "구운몽", new long[] { 1, 2, 11, 12, 13 });
		BookChoice.Candidate owned = new BookChoice.Candidate(2, "구운몽", new long[] { 1, 2, 3, 4 });
		BookChoice.Candidate disabled = new BookChoice.Candidate(3, "구운몽", new long[] { 1, 99 });

		assertThat(BookChoice.choose(List.of(subscriptions, owned, disabled), KINDS)).contains(owned);
	}

	@Test
	void sameCountGivesTheFirstVolumeThenTheSmallerId() { // AC-7
		BookChoice.Candidate second = new BookChoice.Candidate(5, "토지. 2(1부 2권)", new long[] { 1, 2 });
		BookChoice.Candidate first = new BookChoice.Candidate(8, "토지. 1(1부 1권)", new long[] { 3, 4 });
		BookChoice.Candidate noVolume = new BookChoice.Candidate(7, "토지", new long[] { 1, 5 });

		assertThat(BookChoice.choose(List.of(second, first), KINDS)).contains(first);
		assertThat(BookChoice.choose(List.of(second, first, noVolume), KINDS)).contains(noVolume);
		assertThat(BookChoice.choose(List.of(), KINDS)).isEmpty();
	}

}
