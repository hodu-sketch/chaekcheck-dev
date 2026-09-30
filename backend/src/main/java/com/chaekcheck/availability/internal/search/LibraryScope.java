package com.chaekcheck.availability.internal.search;

import java.util.Locale;

import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/** Which libraries of the plan are searched (C2-U-10). */
public enum LibraryScope {

	/** every library in the plan */
	ALL,
	/** only libraries the user can join for sure ({@link SearchTarget#joinable()}) */
	JOINABLE;

	/** Missing means ALL. @return null for an unknown value */
	public static LibraryScope parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ALL;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "all" -> ALL;
			case "joinable" -> JOINABLE;
			default -> null;
		};
	}

	public PlanOptions.Scope option() {
		return PlanOptions.Scope.valueOf(name());
	}

	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
