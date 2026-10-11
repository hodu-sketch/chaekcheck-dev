package com.chaekcheck.availability.internal.search;

/**
 * A holding of the next batch that cannot be looked up (ADR-0070): the holding is not in the catalog any more
 * ({@code holding_not_found}, e.g. its library's list dropped it at the last import), or its library is missing or
 * disabled ({@code library_not_found}).
 */
public record MissingHolding(long holdingId, boolean holdingGone) {

	public static MissingHolding holdingNotFound(long holdingId) {
		return new MissingHolding(holdingId, true);
	}

	public static MissingHolding libraryNotFound(long holdingId) {
		return new MissingHolding(holdingId, false);
	}

	String code() {
		return holdingGone ? "holding_not_found" : "library_not_found";
	}

	String detail() {
		return holdingGone ? "The holding is not in the library's list any more"
				: "The holding's library does not exist or is disabled";
	}

}
