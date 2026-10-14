package com.chaekcheck.auth;

/**
 * Whether a request came from a signed-in browser, for the usage log only (ADR-0102). Never used for permissions.
 */
public enum LoginStatus {

	/** The {@code access_token} cookie carries our signature, even if it has expired. */
	SIGNED_IN("signed_in"),

	/** No cookie, or one that is broken or not signed by us. */
	SIGNED_OUT("signed_out"),

	/** The login feature is off; the cookie is not looked at. */
	LOGIN_OFF("login_off");

	private final String value;

	LoginStatus(String value) {
		this.value = value;
	}

	/** The value stored in the usage log tables. */
	public String value() {
		return value;
	}

}
