package com.chaekcheck.auth.internal.token;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

import javax.crypto.SecretKey;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseCookie;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Service;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.token.RefreshTokenStore.Rotation;

/**
 * Issues the two login cookies. Sign-up, signing in again and refresh all go through here (step 4).
 * <ul>
 * <li>access: JWT HS256 with {@code sub} and {@code exp} only, 15 minutes (C8)</li>
 * <li>refresh: 256 random bits; the database keeps only its SHA-256 (C9)</li>
 * </ul>
 */
@Service
@ConditionalOnAuthEnabled
public class TokenService {

	private static final Logger log = LoggerFactory.getLogger(TokenService.class);

	private static final int REFRESH_BYTES = 32;

	private final RefreshTokenStore store;

	private final JwtEncoder jwtEncoder;

	private final AuthProperties.Tokens settings;

	private final Clock clock;

	private final SecureRandom random = new SecureRandom();

	@Autowired
	TokenService(RefreshTokenStore store, SecretKey accessTokenKey, AuthProperties properties) {
		this(store, NimbusJwtEncoder.withSecretKey(accessTokenKey).algorithm(MacAlgorithm.HS256).build(),
				properties.tokens(), Clock.systemUTC());
	}

	TokenService(RefreshTokenStore store, JwtEncoder jwtEncoder, AuthProperties.Tokens settings, Clock clock) {
		this.store = store;
		this.jwtEncoder = jwtEncoder;
		this.settings = settings;
		this.clock = clock;
	}

	/** A new login for the account: a new refresh row (30 days) and both cookies. */
	public LoginCookies signIn(long accountId) {
		Instant now = clock.instant();
		String refresh = newRefreshValue();
		Instant expiresAt = now.plus(settings.refreshTokenTtl());
		store.insert(accountId, hash(refresh), expiresAt);
		return cookies(accountId, refresh, expiresAt, now);
	}

	/** Swaps the presented refresh value for a new one, or empty when the login is over (then clear the cookies). */
	public Optional<LoginCookies> refresh(String presented) {
		if (presented == null || presented.isBlank()) {
			return Optional.empty();
		}
		Instant now = clock.instant();
		String next = newRefreshValue();
		return switch (store.rotate(hash(presented), hash(next), now)) {
			case Rotation.Rotated rotated -> Optional.of(cookies(rotated.accountId(), next, rotated.expiresAt(), now));
			case Rotation.Reused reused -> {
				// An old value came back: stolen, or a lost response (accepted limit, ADR-0038). That login ends.
				log.warn("Refresh token reused; login ended for account {}", reused.accountId());
				yield Optional.empty();
			}
			case Rotation.Rejected rejected -> Optional.empty();
		};
	}

	/** Ends this browser's login. Without a value there is nothing to delete; the cookies are cleared anyway. */
	public void signOut(String presented) {
		if (presented != null && !presented.isBlank()) {
			store.deleteByValue(hash(presented));
		}
	}

	/**
	 * The account the browser is signed in as, read from its refresh value: the withdrawal re-authentication callback
	 * compares it with the account that just signed in again (step 8). Empty when the login is over.
	 */
	public Optional<Long> accountOfRefresh(String presented) {
		if (presented == null || presented.isBlank()) {
			return Optional.empty();
		}
		return store.accountOf(hash(presented), clock.instant());
	}

	/** The daily clean-up (C31). */
	int deleteExpired() {
		return store.deleteExpired(clock.instant());
	}

	private LoginCookies cookies(long accountId, String refresh, Instant expiresAt, Instant now) {
		JwtClaimsSet claims = JwtClaimsSet.builder()
			.subject(String.valueOf(accountId))
			.expiresAt(now.plus(settings.accessTokenTtl()))
			.build();
		String jwt = jwtEncoder
			.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
			.getTokenValue();
		return new LoginCookies(TokenCookies.access(jwt, settings.accessTokenTtl()),
				TokenCookies.refresh(refresh, Duration.between(now, expiresAt)));
	}

	private String newRefreshValue() {
		byte[] bytes = new byte[REFRESH_BYTES];
		random.nextBytes(bytes);
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("SHA-256 is part of every Java runtime", ex);
		}
	}

	/** The two {@code Set-Cookie} values of a login. */
	public record LoginCookies(ResponseCookie access, ResponseCookie refresh) {
	}

}
