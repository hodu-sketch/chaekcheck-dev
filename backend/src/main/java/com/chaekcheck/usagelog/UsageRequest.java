package com.chaekcheck.usagelog;

import java.time.Instant;

/**
 * What the usage log took from a request on its own thread (cycle 15 C3). Made only by {@link UsageLog#capture}.
 * @param receivedAt when the request came in; the rows keep this time (spec R1-4)
 * @param clientIp from {@code X-Real-IP}, null when missing or not an IP address
 * @param loginStatus {@code signed_in}, {@code signed_out} or {@code login_off}
 * @param recorded false when logging is off or the request comes from a robot: nothing is recorded for it
 */
public record UsageRequest(Instant receivedAt, String clientIp, String loginStatus, boolean recorded) {

	/** Records nothing. */
	public static final UsageRequest NOT_RECORDED = new UsageRequest(Instant.EPOCH, null, null, false);

}
