package com.chaekcheck.foryou.internal.web;

import java.util.ArrayList;
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

	/**
	 * A pick passes when at least this many plan libraries hold it (user 2026-10-29 "문턱 3으로": books held by too few
	 * libraries are likely all on loan; cycle 23 threshold test, joinable plans: available now 48 → 61%, one-library
	 * picks 11% → 0%, blind judge unchanged).
	 */
	static final int MIN_PLAN_LIBRARIES = 3;

	private SimilarOrder() {
	}

	/**
	 * The picks that pass first, each part in its order: a pick passes when a plan library that lends without a copy
	 * limit holds it ({@code instantLibraryIds}: lending model SUBSCRIPTION or RENTAL) or at least
	 * {@value #MIN_PLAN_LIBRARIES} plan libraries hold it. The others follow, so they only show when too few pass.
	 * @param libraryIds each pick's holding library ids ({@code catalog.book.library_ids}); a pick without an entry
	 * never passes
	 */
	static List<Long> availableFirst(List<Long> picks, Map<Long, long[]> libraryIds, Set<Long> planIds,
			Set<Long> instantLibraryIds) {
		List<Long> passed = new ArrayList<>(picks.size());
		List<Long> rest = new ArrayList<>();
		for (long pick : picks) {
			int inPlan = 0;
			boolean instant = false;
			for (long libraryId : libraryIds.getOrDefault(pick, new long[0])) {
				if (planIds.contains(libraryId)) {
					inPlan++;
					instant |= instantLibraryIds.contains(libraryId);
				}
			}
			(instant || inPlan >= MIN_PLAN_LIBRARIES ? passed : rest).add(pick);
		}
		passed.addAll(rest);
		return passed;
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
