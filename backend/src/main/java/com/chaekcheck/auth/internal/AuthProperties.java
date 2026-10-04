package com.chaekcheck.auth.internal;

import java.time.Duration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Login settings. Bound and checked only when {@code chaekcheck.auth.enabled=true}: a missing key stops the start
 * with the property name and the environment variable to set. Secret values come from environment variables only
 * (never written in {@code application.yml}, tests, commits or logs).
 *
 * @param publicBaseUrl the address users open the site with (scheme, host, port), e.g. {@code http://localhost:5173}
 * in development. The providers' redirect URIs are built from it, never from the request (C18).
 */
@Validated
@ConfigurationProperties("chaekcheck.auth")
public record AuthProperties(@Valid Jwt jwt, @Valid Provider naver, @Valid Provider kakao, Tokens tokens,
		@NotBlank(message = "is required when login is on (environment variable AUTH_PUBLIC_BASE_URL)")
		@Pattern(regexp = "https?://[^/\\s]+",
				message = "must be scheme, host and optional port only, e.g. http://localhost:5173 (AUTH_PUBLIC_BASE_URL)") String publicBaseUrl,
		ProviderCalls providerCalls, @Valid Documents documents) {

	public AuthProperties {
		jwt = jwt != null ? jwt : new Jwt(null);
		naver = naver != null ? naver : new Provider(null, null, null, null, null, null, null);
		kakao = kakao != null ? kakao : new Provider(null, null, null, null, null, null, null);
		tokens = tokens != null ? tokens : new Tokens(null, null);
		providerCalls = providerCalls != null ? providerCalls : new ProviderCalls(null, null);
		documents = documents != null ? documents : new Documents(null, null);
	}

	/** Kakao is unlinked with the admin key (abandoned sign-up, withdrawal, link check); Naver needs none. */
	@AssertTrue(message = "chaekcheck.auth.kakao.admin-key is required when login is on (environment variable KAKAO_ADMIN_KEY)")
	public boolean isKakaoAdminKeySet() {
		return kakao.adminKey() != null && !kakao.adminKey().isBlank();
	}

	/** HS256 signing key, base64 of at least 32 random bytes (design-delta). */
	public record Jwt(@NotBlank(message = "is required when login is on (environment variable AUTH_JWT_SECRET)") String secret) {
	}

	/**
	 * One provider: its keys (environment variables) and its public endpoints (in {@code application.yml}).
	 *
	 * @param unlinkUri Naver: token revoke; Kakao: unlink with the admin key
	 * @param adminKey Kakao's admin key; not used for Naver
	 */
	public record Provider(
			@NotBlank(message = "is required when login is on (see .env.example for the environment variable)") String clientId,
			@NotBlank(message = "is required when login is on (see .env.example for the environment variable)") String clientSecret,
			@NotBlank String authorizationUri, @NotBlank String tokenUri, @NotBlank String userInfoUri,
			@NotBlank String unlinkUri, String adminKey) {

		/** Keeps the secrets out of logs and error messages. */
		@Override
		public String toString() {
			return "Provider[clientId=" + clientId + ", authorizationUri=" + authorizationUri + "]";
		}

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

	/** Calls to Naver and Kakao: connect 3 s, read 5 s [확정] (C20). */
	public record ProviderCalls(Duration connectTimeout, Duration readTimeout) {

		public ProviderCalls {
			connectTimeout = connectTimeout != null ? connectTimeout : Duration.ofSeconds(3);
			readTimeout = readTimeout != null ? readTimeout : Duration.ofSeconds(5);
		}

	}

	/**
	 * Current versions of the documents a user agrees to at sign-up: the effective date, {@code YYYY-MM-DD}. The
	 * server setting is the source; sign-up with another version is 409 (C27).
	 */
	public record Documents(
			@NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "must be YYYY-MM-DD") String termsOfServiceVersion,
			@NotNull @Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}", message = "must be YYYY-MM-DD") String privacyCollectionVersion) {
	}

}
