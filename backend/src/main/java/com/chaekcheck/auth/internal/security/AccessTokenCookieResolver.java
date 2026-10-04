package com.chaekcheck.auth.internal.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.web.util.matcher.RequestMatcher;

/**
 * Reads the access token from the {@code access_token} cookie, and only on the protected paths. A public API
 * request with a broken or expired token cookie (or any {@code Authorization} header) is never rejected, because
 * the token is not read there at all (AC-17). The {@code Authorization} header is never read.
 */
final class AccessTokenCookieResolver implements BearerTokenResolver {

	private final RequestMatcher protectedPaths;

	AccessTokenCookieResolver(RequestMatcher protectedPaths) {
		this.protectedPaths = protectedPaths;
	}

	@Override
	public String resolve(HttpServletRequest request) {
		if (!protectedPaths.matches(request) || request.getCookies() == null) {
			return null;
		}
		for (Cookie cookie : request.getCookies()) {
			if (AuthCookies.ACCESS_TOKEN.equals(cookie.getName()) && !cookie.getValue().isBlank()) {
				return cookie.getValue();
			}
		}
		return null;
	}

}
