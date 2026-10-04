package com.chaekcheck.library;

import java.util.List;
import java.util.Set;

/**
 * Which libraries of a plan are kept (C2-U-10 scope, "방문 없이 가입" visit filter) and which the user added by hand
 * (cycle 06 {@code libraryIds}). Shared by the site search, the book search and the holding stream, so all of them
 * work on the same plan (design-delta cycle 04 "모듈").
 * @param libraryIds libraries the user added by hand; enabled ones are always in the plan, first, and kept by both
 * filters (ADR-0050). Unknown or disabled ids are ignored. May be empty.
 */
public record PlanOptions(Scope scope, Visit visit, Set<Long> libraryIds) {

	/** Every library of the plan. */
	public static final PlanOptions DEFAULT = new PlanOptions(Scope.ALL, Visit.ANY);

	public PlanOptions {
		if (scope == null || visit == null || libraryIds == null) {
			throw new IllegalArgumentException("scope, visit and libraryIds are required");
		}
		libraryIds = Set.copyOf(libraryIds);
	}

	/** No library added by hand. */
	public PlanOptions(Scope scope, Visit visit) {
		this(scope, visit, Set.of());
	}

	public List<SearchTarget> apply(List<SearchTarget> plan) {
		return plan.stream()
			.filter(target -> scope == Scope.ALL || target.joinable() || target.chosen())
			.filter(target -> visit == Visit.ANY || !target.visitRequired() || target.chosen())
			.toList();
	}

	public enum Scope {

		/** every library in the plan */
		ALL,
		/** only libraries the user can join for sure ({@link SearchTarget#joinable()}), and the chosen ones */
		JOINABLE

	}

	public enum Visit {

		/** every library, with or without a visit */
		ANY,
		/**
		 * only libraries one can join without a visit ({@link SearchTarget#visitRequired()} false), and the chosen
		 * ones
		 */
		NONE

	}

}
