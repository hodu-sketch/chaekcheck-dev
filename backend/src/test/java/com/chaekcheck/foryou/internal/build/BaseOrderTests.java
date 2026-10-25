package com.chaekcheck.foryou.internal.build;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chaekcheck.foryou.internal.build.BaseOrder.Entry;

/** R2-4 (cycle 23 step 5, AC-4): both first, then popular and listed one after the other, 40 at most. */
class BaseOrderTests {

	@Test
	void popularAndListedComeFirstThenTheyAlternate() {
		Map<Long, Double> scores = Map.of(1L, 90.0, 2L, 80.0, 3L, 70.0, 4L, 60.0, 5L, 0.0);
		Map<Long, Integer> listed = Map.of(2L, 5, 4L, 1, 5L, 9, 6L, 9, 7L, 3);

		List<Entry> order = BaseOrder.order(scores, listed, 40);

		// ① 2 and 4 (popular and listed, by score); ② popular-only 1, 3 against listed-only 5, 6 (9 libraries, smaller
		// id first; 5 has score 0 so it is not popular), 7
		assertThat(order).containsExactly(new Entry(2, true), new Entry(4, true), new Entry(1, true), new Entry(5, false),
				new Entry(3, true), new Entry(6, false), new Entry(7, false));
	}

	@Test
	void atMostTheSize() {
		Map<Long, Double> scores = new HashMap<>();
		Map<Long, Integer> listed = new HashMap<>();
		for (long i = 1; i <= 30; i++) {
			scores.put(i, 100.0 - i);
			listed.put(100 + i, 1);
		}

		List<Entry> order = BaseOrder.order(scores, listed, 40);

		assertThat(order).hasSize(40);
		assertThat(order.subList(0, 4)).containsExactly(new Entry(1, true), new Entry(101, false), new Entry(2, true),
				new Entry(102, false));
	}

	@Test
	void onlyListedBooksWhenNoPopularSource() {
		assertThat(BaseOrder.order(Map.of(), Map.of(8L, 2, 9L, 4), 40)).containsExactly(new Entry(9, false),
				new Entry(8, false));
		assertThat(BaseOrder.order(Map.of(), Map.of(), 40)).isEmpty();
	}

}
