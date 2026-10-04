package com.chaekcheck.auth.internal.token;

import java.time.Duration;
import java.util.List;

import org.springframework.http.ResponseCookie;

import com.chaekcheck.auth.internal.security.AuthCookies;

/**
 * The login cookies (C11): Secure, HttpOnly, SameSite=Lax, no Domain. A cookie is cleared with the same name,
 * path and attributes it was set with, so the browser replaces it instead of keeping a second one.
 */
public final class TokenCookies {

	private TokenCookies() {
	}

	static ResponseCookie access(String jwt, Duration maxAge) {
		return cookie(AuthCookies.ACCESS_TOKEN, jwt, AuthCookies.ACCESS_TOKEN_PATH, maxAge);
	}

	static ResponseCookie refresh(String value, Duration maxAge) {
		return cookie(AuthCookies.REFRESH_TOKEN, value, AuthCookies.REFRESH_TOKEN_PATH,
				maxAge.isNegative() ? Duration.ZERO : maxAge);
	}

	/** Both login cookies, expired (logout, failed refresh, withdrawal). */
	public static List<ResponseCookie> cleared() {
		return List.of(cookie(AuthCookies.ACCESS_TOKEN, "", AuthCookies.ACCESS_TOKEN_PATH, Duration.ZERO),
				cookie(AuthCookies.REFRESH_TOKEN, "", AuthCookies.REFRESH_TOKEN_PATH, Duration.ZERO));
	}

	private static ResponseCookie cookie(String name, String value, String path, Duration maxAge) {
		return ResponseCookie.from(name, value)
			.path(path)
			.maxAge(maxAge)
			.secure(true)
			.httpOnly(true)
			.sameSite("Lax")
			.build();
	}

}
