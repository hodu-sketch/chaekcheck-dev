package com.chaekcheck.popular.internal.fetch;

/** Why a fetch failed: {@code popular.fetch_run.failure_reason} (plan cycle 10 design #12). */
public enum FailureReason {

	/** the rank page did not answer 200 or had no CSRF token */
	SESSION_FAILED("session_failed"),
	/** a ranking or sales request did not answer 200 (302 to accessDenied, 403, ...) */
	REQUEST_REJECTED("request_rejected"),
	/** an answer did not have the expected shape (no book list, empty title, no sales count, too large) */
	FORMAT_CHANGED("format_changed"),
	/** a list had fewer than 50 books */
	TOO_FEW_BOOKS("too_few_books"),
	/** connection failure or time-out */
	NETWORK_ERROR("network_error");

	private final String value;

	FailureReason(String value) {
		this.value = value;
	}

	/** The stored value. */
	public String value() {
		return value;
	}

}
