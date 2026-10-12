package com.chaekcheck.picks.internal.fetch;

/**
 * A fetch stopped: the stored list stays, one FAILED run is written and the next day tries again (ADR-0092). The
 * message is the {@code failure_detail}: an HTTP status, an API error code or which value was wrong. It never holds the
 * key, the request URL or the response body (spec R1-7).
 */
public final class FetchFailure extends RuntimeException {

	private final FailureReason reason;

	public FetchFailure(FailureReason reason, String detail) {
		super(detail, null, false, false);
		this.reason = reason;
	}

	public FailureReason reason() {
		return reason;
	}

}
