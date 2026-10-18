package com.chaekcheck.usagelog;

import java.time.Instant;

/**
 * What the usage log took from a request on its own thread (cycle 15 C3). Made only by {@link UsageLog#capture}.
 * @param receivedAt when the request came in; the rows keep this time (spec R1-4)
 * @param clientIp from {@code X-Real-IP}, null when missing or not an IP address
 * @param loginStatus {@code signed_in}, {@code signed_out} or {@code login_off}
 * @param accountId the signed-in member's number from the login cookie (ADR-0134), null when signed out or login off
 * @param visitKey the browser tab's random key from {@code X-Visit-Key} (ADR-0134), null when missing or of the wrong
 * shape; kept only by the book view, library click and screen event rows
 * @param recorded false when logging is off or the request comes from a robot: nothing is recorded for it
 */
public record UsageRequest(Instant receivedAt, String clientIp, String loginStatus, Long accountId, String visitKey,
		boolean recorded) {

	/** Records nothing. */
	public static final UsageRequest NOT_RECORDED = new UsageRequest(Instant.EPOCH, null, null, null, null, false);

}
