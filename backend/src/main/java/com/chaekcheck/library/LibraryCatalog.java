package com.chaekcheck.library;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Public API of the library module.
 */
public interface LibraryCatalog {

	/**
	 * The search plan: enabled libraries the user may search, in priority order (design-delta "우선순위 그룹
	 * 계산"). Groups in {@link PriorityGroup} order; inside a group non-deferred first, then holdings count
	 * descending (unknown last), then id. With regions, libraries whose only rules are region rules that do not
	 * match are left out; unverified libraries stay.
	 * @param residenceIds chosen SIGUNGU ids for residence, may be empty
	 * @param workIds chosen SIGUNGU ids for work, may be empty
	 * @throws InvalidRegionException if any id is abolished, unknown or a SIDO id
	 */
	default List<SearchTarget> planSearch(Set<Long> residenceIds, Set<Long> workIds) {
		return planSearch(residenceIds, workIds, PlanOptions.DEFAULT);
	}

	/**
	 * The search plan in priority order, keeping only the libraries {@code options} allows (scope, visit). Enabled
	 * libraries in {@link PlanOptions#libraryIds()} come first with {@link SearchTarget#chosen()}, even when their
	 * region rules do not match, and both filters keep them (ADR-0050).
	 * @throws InvalidRegionException if any id is abolished, unknown or a SIDO id
	 */
	List<SearchTarget> planSearch(Set<Long> residenceIds, Set<Long> workIds, PlanOptions options);

	/** Every enabled library with its site root, in id order (catalog importer comparison). */
	List<LibraryRef> enabledLibraryRefs();

	/**
	 * One enabled library as a target, grouped as if no regions were sent; empty when it does not exist or is
	 * disabled.
	 */
	Optional<SearchTarget> findEnabledTarget(long libraryId);

}
