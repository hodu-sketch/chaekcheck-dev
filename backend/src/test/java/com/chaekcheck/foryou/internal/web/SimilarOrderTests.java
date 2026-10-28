package com.chaekcheck.foryou.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** The order of the personal picks (user 2026-10-25 "순서 고치기 하고"). */
class SimilarOrderTests {

	@Test
	void theNearestOfEachBaseBookFirstThenTheSecondNearest() {
		Map<Long, int[]> neighbours = Map.of(1L, new int[] { 11, 12, 13 }, 2L, new int[] { 21, 11 }, 3L, new int[0]);

		assertThat(SimilarOrder.byCloseness(List.of(1L, 2L, 3L, 4L), neighbours)).containsExactly(11L, 21L, 12L, 13L);
	}

	@Test
	void picksHeldByThreePlanLibrariesOrAnInstantOneComeFirstInTheirOrder() {
		Set<Long> plan = Set.of(1L, 2L, 3L, 9L);
		Set<Long> instant = Set.of(9L);
		Map<Long, long[]> libraries = Map.of(
				11L, new long[] { 1 },              // one plan library: waits
				12L, new long[] { 1, 2, 3 },        // three: passes
				13L, new long[] { 9 },              // an instant plan library: passes
				14L, new long[] { 1, 2, 7, 8 },     // two in the plan (7, 8 are not): waits
				15L, new long[] { 1, 2, 3, 4 });    // passes

		assertThat(SimilarOrder.availableFirst(List.of(11L, 12L, 13L, 14L, 15L, 16L), libraries, plan, instant))
			.containsExactly(12L, 13L, 15L, 11L, 14L, 16L);
	}

}
