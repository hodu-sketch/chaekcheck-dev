package com.chaekcheck.availability.internal.search;

import java.util.Locale;

import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * "방문 없이 가입" (user request 2026-09-29): with NONE, libraries whose join needs a visit
 * ({@link SearchTarget#visitRequired()}) are left out of the plan, so they are never requested.
 */
public enum VisitFilter {

	/** every library, with or without a visit */
	ANY,
	/** only libraries one can join without a visit */
	NONE;

	/** Missing means ANY. @return null for an unknown value */
	public static VisitFilter parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ANY;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "any" -> ANY;
			case "none" -> NONE;
			default -> null;
		};
	}

	public PlanOptions.Visit option() {
		return PlanOptions.Visit.valueOf(name());
	}

	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
