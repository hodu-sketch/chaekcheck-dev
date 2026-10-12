package com.chaekcheck.picks.internal.fetch;

/** Why a fetch failed: {@code picks.fetch_run.failure_reason} [확정] (plan cycle 14 design-delta "데이터"). */
public enum FailureReason {

	/** no API key set: no request was sent */
	KEY_MISSING("key_missing"),
	/** connection failure or time-out */
	NETWORK_ERROR("network_error"),
	/** an answer that is not 200 (3xx included), or an API error code */
	REQUEST_REJECTED("request_rejected"),
	/** an answer without the expected shape, or one too large */
	FORMAT_CHANGED("format_changed"),
	/** {@code totalCount} is larger than the rows received */
	INCOMPLETE("incomplete"),
	/** no book in the latest six months */
	NO_BOOKS("no_books");

	private final String value;

	FailureReason(String value) {
		this.value = value;
	}

	/** The stored value. */
	public String value() {
		return value;
	}

}
