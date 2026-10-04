package com.chaekcheck.catalog.internal.search;

import com.chaekcheck.library.SearchTarget;

/**
 * How easily the user can use a library, for the first key of the book order (spec 3.5, design-delta
 * {@code joinTier} [안 값]). Declaration order is the sort order. Same rule as the library order inside a book
 * (ADR-0027): joinable libraries without a visit by group, then joinable ones with a visit, then the rest.
 * Without regions only CHOSEN, OPEN, VISIT_ONLY and NEEDS_CHECK occur. CHOSEN (a library the user added by hand)
 * comes before everything (cycle 06, ADR-0050). The SQL sorts by {@link #ordinal()}, so adding a value only moves
 * the numbers.
 */
public enum JoinTier {

	CHOSEN, MY_SIGUNGU, MY_SIDO, OPEN, VISIT_ONLY, NEEDS_CHECK;

	public static JoinTier of(SearchTarget target) {
		if (target.chosen()) {
			return CHOSEN;
		}
		if (!target.joinable()) {
			return NEEDS_CHECK;
		}
		if (target.visitRequired()) {
			return VISIT_ONLY;
		}
		return switch (target.priorityGroup()) {
			case MY_SIGUNGU -> MY_SIGUNGU;
			case MY_SIDO -> MY_SIDO;
			default -> OPEN;
		};
	}

}
