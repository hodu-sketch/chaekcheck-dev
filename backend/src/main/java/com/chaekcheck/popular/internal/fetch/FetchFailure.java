package com.chaekcheck.popular.internal.fetch;

/**
 * A fetch stopped: nothing of the month is stored, one FAILED run is written and the next day tries again (spec
 * R1-3). The message is the {@code failure_detail}: which list, which status.
 */
public final class FetchFailure extends RuntimeException {

	private final FailureReason reason;

	public FetchFailure(FailureReason reason, String detail) {
		super(detail);
		this.reason = reason;
	}

	public FetchFailure(FailureReason reason, String detail, Throwable cause) {
		super(detail, cause);
		this.reason = reason;
	}

	public FailureReason reason() {
		return reason;
	}

}
