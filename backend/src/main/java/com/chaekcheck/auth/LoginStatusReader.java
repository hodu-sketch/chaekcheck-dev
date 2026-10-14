package com.chaekcheck.auth;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Tells whether a request comes from a signed-in browser, for the usage log (ADR-0102). Login on: checks only the
 * HS256 signature of the {@code access_token} cookie, so an expired token still counts as signed in. Login off: the
 * cookie is not read and the answer is always {@link LoginStatus#LOGIN_OFF}. Never throws, and never decides access.
 */
public interface LoginStatusReader {

	LoginStatus read(HttpServletRequest request);

}
