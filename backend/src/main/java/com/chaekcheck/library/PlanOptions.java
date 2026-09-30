package com.chaekcheck.library;

import java.util.List;

/**
 * Which libraries of a plan are kept (C2-U-10 scope, "방문 없이 가입" visit filter). Shared by the site search,
 * the book search and the holding stream, so all of them work on the same plan (design-delta cycle 04 "모듈").
 */
public record PlanOptions(Scope scope, Visit visit) {

	/** Every library of the plan. */
	public static final PlanOptions DEFAULT = new PlanOptions(Scope.ALL, Visit.ANY);

	public PlanOptions {
		if (scope == null || visit == null) {
			throw new IllegalArgumentException("scope and visit are required");
		}
	}

	public List<SearchTarget> apply(List<SearchTarget> plan) {
		return plan.stream()
			.filter(target -> scope == Scope.ALL || target.joinable())
			.filter(target -> visit == Visit.ANY || !target.visitRequired())
			.toList();
	}

	public enum Scope {

		/** every library in the plan */
		ALL,
		/** only libraries the user can join for sure ({@link SearchTarget#joinable()}) */
		JOINABLE

	}

	public enum Visit {

		/** every library, with or without a visit */
		ANY,
		/** only libraries one can join without a visit ({@link SearchTarget#visitRequired()} false) */
		NONE

	}

}
