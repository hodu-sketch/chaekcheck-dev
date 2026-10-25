package com.chaekcheck.foryou.internal.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The order of the personal picks (user 2026-10-25 "순서 고치기 하고"). */
class SimilarOrderTests {

	@Test
	void theNearestOfEachBaseBookFirstThenTheSecondNearest() {
		Map<Long, int[]> neighbours = Map.of(1L, new int[] { 11, 12, 13 }, 2L, new int[] { 21, 11 }, 3L, new int[0]);

		assertThat(SimilarOrder.byCloseness(List.of(1L, 2L, 3L, 4L), neighbours)).containsExactly(11L, 21L, 12L, 13L);
	}

}
