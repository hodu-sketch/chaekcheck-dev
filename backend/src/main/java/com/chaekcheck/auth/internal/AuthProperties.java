package com.chaekcheck.auth.internal;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Login settings. Bound and checked only when {@code chaekcheck.auth.enabled=true}: a missing key stops the start
 * with the property name and the environment variable to set. Secret values come from environment variables only
 * (never written in {@code application.yml}, tests, commits or logs).
 */
@Validated
@ConfigurationProperties("chaekcheck.auth")
public record AuthProperties(@Valid Jwt jwt, @Valid Provider naver, @Valid Provider kakao, Tokens tokens) {

	public AuthProperties {
		jwt = jwt != null ? jwt : new Jwt(null);
		naver = naver != null ? naver : new Provider(null, null);
		kakao = kakao != null ? kakao : new Provider(null, null);
		tokens = tokens != null ? tokens : new Tokens(null, null);
	}

	/** HS256 signing key, base64 of at least 32 random bytes (design-delta). */
	public record Jwt(@NotBlank(message = "is required when login is on (environment variable AUTH_JWT_SECRET)") String secret) {
	}

	public record Provider(
			@NotBlank(message = "is required when login is on (see .env.example for the environment variable)") String clientId,
			@NotBlank(message = "is required when login is on (see .env.example for the environment variable)") String clientSecret) {
	}

	/**
	 * @param accessTokenTtl JWT lifetime, 15 minutes [확정] (ADR-0038)
	 * @param refreshTokenTtl one login, 30 days from sign-in, not extended by refresh [확정]
	 */
	public record Tokens(Duration accessTokenTtl, Duration refreshTokenTtl) {

		public Tokens {
			accessTokenTtl = accessTokenTtl != null ? accessTokenTtl : Duration.ofMinutes(15);
			refreshTokenTtl = refreshTokenTtl != null ? refreshTokenTtl : Duration.ofDays(30);
		}

	}

}
