package com.chaekcheck.auth.internal.security;

/** Cookie names and paths (handoff C11-C13). Clearing a cookie must use the same name, path and attributes. */
public final class AuthCookies {

	public static final String ACCESS_TOKEN = "access_token";

	/**
	 * The access token goes with every API request (ADR-0102). Only the account API reads it as a login; the usage log
	 * only checks its signature ({@code LoginStatusReader}). Cookies on the old path {@code /api/v1/me} are not cleared:
	 * they expire within 15 minutes.
	 */
	public static final String ACCESS_TOKEN_PATH = "/api/v1";

	public static final String REFRESH_TOKEN = "refresh_token";

	/** The refresh token is sent only to refresh and logout. */
	public static final String REFRESH_TOKEN_PATH = "/api/v1/auth";

	/** Read by the web scripts on every page, so its path is the site root. */
	public static final String CSRF_TOKEN = "XSRF-TOKEN";

	public static final String CSRF_HEADER = "X-XSRF-TOKEN";

	private AuthCookies() {
	}

}
