package com.chaekcheck.auth.internal.login;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * A provider account that is not signed up yet, kept only in the {@code HttpSession} (10 minutes) until the user
 * agrees or cancels (ADR-0040). Nothing is written to the database before sign-up. Serializable so a later
 * Spring Session store works unchanged (C16).
 *
 * @param naverAccessToken Naver's access token, kept only to revoke the link if the sign-up is abandoned (step 6);
 * {@code null} for Kakao, which is unlinked with the admin key. Never logged or stored.
 */
public record PendingSignup(String method, String subject, Instant createdAt, String naverAccessToken)
		implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public static final String SESSION_ATTRIBUTE = PendingSignup.class.getName();

	/** Keeps the token out of logs and error messages. */
	@Override
	public String toString() {
		return "PendingSignup[method=" + method + ", createdAt=" + createdAt + "]";
	}

}
