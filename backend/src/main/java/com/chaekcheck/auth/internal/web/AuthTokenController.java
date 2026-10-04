package com.chaekcheck.auth.internal.web;

import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.security.AuthCookies;
import com.chaekcheck.auth.internal.token.TokenCookies;
import com.chaekcheck.auth.internal.token.TokenService;

/**
 * Refresh and logout. Both are POST and need the CSRF token (C4); the refresh cookie is sent only to
 * {@code /api/v1/auth} (C11).
 */
@RestController
@ConditionalOnAuthEnabled
class AuthTokenController {

	private final TokenService tokens;

	AuthTokenController(TokenService tokens) {
		this.tokens = tokens;
	}

	/** 204 with both new cookies, or 401 {@code unauthorized} with both cookies cleared. */
	@PostMapping("/api/v1/auth/refresh")
	ResponseEntity<ProblemDetail> refresh(
			@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken,
			HttpServletRequest request) {
		return tokens.refresh(refreshToken)
			.map(cookies -> ResponseEntity.noContent()
				.header(HttpHeaders.SET_COOKIE, cookies.access().toString(), cookies.refresh().toString())
				.<ProblemDetail>build())
			.orElseGet(() -> unauthorized(request));
	}

	/** Idempotent: 204 and cleared cookies whether or not the login still existed. */
	@PostMapping("/api/v1/auth/logout")
	ResponseEntity<Void> logout(@CookieValue(name = AuthCookies.REFRESH_TOKEN, required = false) String refreshToken) {
		tokens.signOut(refreshToken);
		return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cleared()).build();
	}

	private static ResponseEntity<ProblemDetail> unauthorized(HttpServletRequest request) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Login required");
		problem.setInstance(URI.create(request.getRequestURI()));
		problem.setProperty("code", "unauthorized");
		return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
			.header(HttpHeaders.SET_COOKIE, cleared())
			.contentType(MediaType.APPLICATION_PROBLEM_JSON)
			.body(problem);
	}

	private static String[] cleared() {
		return TokenCookies.cleared().stream().map(ResponseCookie::toString).toArray(String[]::new);
	}

}
