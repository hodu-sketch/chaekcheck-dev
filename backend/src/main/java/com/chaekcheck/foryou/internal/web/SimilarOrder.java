package com.chaekcheck.foryou.internal.web;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The order of the personal picks (user 2026-10-25 "순서 고치기 하고"): the neighbours by how close they are, the base
 * books taking turns, instead of by how many libraries hold them. Measured in the cycle 23 offline scores against
 * moving near copies and repeated authors to the end (lower AI judge score, fields no more spread) and against slots
 * per field (fields more spread but a lower AI judge score): this plain order had the best AI judge score
 * (plan-feedback 「순서 고치기」).
 */
final class SimilarOrder {

	private SimilarOrder() {
	}

	/**
	 * The neighbours of the base books, the nearest of each base book first, then the second nearest of each, and so
	 * on; each book once.
	 */
	static List<Long> byCloseness(List<Long> base, Map<Long, int[]> neighbours) {
		Set<Long> out = new LinkedHashSet<>();
		int longest = 0;
		for (long bookId : base) {
			longest = Math.max(longest, neighbours.getOrDefault(bookId, new int[0]).length);
		}
		for (int rank = 0; rank < longest; rank++) {
			for (long bookId : base) {
				int[] near = neighbours.getOrDefault(bookId, new int[0]);
				if (rank < near.length) {
					out.add((long) near[rank]);
				}
			}
		}
		return List.copyOf(out);
	}

}
