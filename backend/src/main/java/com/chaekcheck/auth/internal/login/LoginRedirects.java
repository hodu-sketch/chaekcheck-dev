package com.chaekcheck.auth.internal.login;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;

/**
 * 302 to a screen path with a relative {@code Location}, never built from the request's {@code Host}: behind the
 * Vite proxy ({@code changeOrigin: true}) that host is the backend's, not the one the browser shows (C18). The
 * browser resolves the path against the address it is on.
 */
final class LoginRedirects {

	static final String COMPLETE = "/auth/complete?result=";

	static final String SIGNUP = "/signup";

	private LoginRedirects() {
	}

	static void to(HttpServletResponse response, String path) {
		response.setStatus(HttpStatus.FOUND.value());
		response.setHeader(HttpHeaders.LOCATION, path);
	}

	/** Only codes from the design-delta table go into the address (C21). */
	static void complete(HttpServletResponse response, String resultCode) {
		to(response, COMPLETE + resultCode);
	}

	static void complete(HttpServletResponse response, String resultCode, LoginProvider provider) {
		to(response, COMPLETE + resultCode + "&provider=" + provider.registrationId());
	}

}
