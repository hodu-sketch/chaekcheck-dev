package com.chaekcheck.availability.internal.search;

import java.util.Locale;

/** Which rows count toward a batch's row limit (U-3, design-delta "셈하는 줄"). */
public enum ResultFilter {
	/** every decided row */
	ALL,
	/** AVAILABLE rows only */
	AVAILABLE;

	/** Missing means ALL. @return null for an unknown value */
	public static ResultFilter parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return ALL;
		}
		return switch (raw.strip().toLowerCase(Locale.ROOT)) {
			case "all" -> ALL;
			case "available" -> AVAILABLE;
			default -> null;
		};
	}

	public String value() {
		return name().toLowerCase(Locale.ROOT);
	}

}
