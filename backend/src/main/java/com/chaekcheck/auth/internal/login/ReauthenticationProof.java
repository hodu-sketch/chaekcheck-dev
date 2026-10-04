package com.chaekcheck.auth.internal.login;

import java.io.Serial;
import java.io.Serializable;
import java.time.Instant;

/**
 * "This account's owner signed in again at the provider just now", kept in the {@code HttpSession} between the
 * re-authentication callback and {@code DELETE /api/v1/me}, valid for 5 minutes (ADR-0040, ADR-0043).
 *
 * @param naverAccessToken Naver's access token from the re-authentication, used once to revoke the link; {@code null}
 * for Kakao (unlinked with the admin key). Never logged or stored.
 */
public record ReauthenticationProof(long accountId, String method, Instant verifiedAt, String naverAccessToken)
		implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	public static final String SESSION_ATTRIBUTE = ReauthenticationProof.class.getName();

	/** Keeps the token out of logs and error messages. */
	@Override
	public String toString() {
		return "ReauthenticationProof[accountId=" + accountId + ", method=" + method + ", verifiedAt=" + verifiedAt + "]";
	}

}
