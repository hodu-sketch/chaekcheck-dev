package com.chaekcheck.auth;

/**
 * What {@link LoginStatusReader} tells about one request, for the usage log only (ADR-0102, ADR-0134): the
 * {@link LoginStatus} and, when signed in, the account number from the token's subject. Never used for permissions.
 * @param status signed in, signed out or login off
 * @param accountId the account number when {@code status} is {@link LoginStatus#SIGNED_IN} and the subject is a
 * positive whole number; otherwise null. Not looked up in the database: an account deleted since the token was issued
 * still appears here, and the usage log clears it on {@link AccountDeleted}.
 */
public record LoginState(LoginStatus status, Long accountId) {

	/** No cookie, or one that is broken or not signed by us. */
	public static final LoginState SIGNED_OUT = new LoginState(LoginStatus.SIGNED_OUT, null);

	/** The login feature is off; the cookie is not looked at. */
	public static final LoginState LOGIN_OFF = new LoginState(LoginStatus.LOGIN_OFF, null);

	public LoginState {
		if (status == null) {
			throw new IllegalArgumentException("status is required");
		}
		if (accountId != null && (status != LoginStatus.SIGNED_IN || accountId <= 0)) {
			throw new IllegalArgumentException("accountId only with SIGNED_IN and positive: " + status);
		}
	}

	/** Our signature on the cookie; {@code accountId} null when the subject is not an account number. */
	public static LoginState signedIn(Long accountId) {
		return new LoginState(LoginStatus.SIGNED_IN, accountId);
	}

}
