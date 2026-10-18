package com.chaekcheck.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Tells whether a request comes from a signed-in browser, for the usage log (ADR-0102). Login on: checks only the
 * HS256 signature of the {@code access_token} cookie, so an expired token still counts as signed in, and then gives
 * the account number from the token's subject (ADR-0134). Login off: the cookie is not read and the answer is always
 * {@link LoginState#LOGIN_OFF}. Never throws, and never decides access.
 */
public interface LoginStatusReader {

	LoginState read(HttpServletRequest request);

}
