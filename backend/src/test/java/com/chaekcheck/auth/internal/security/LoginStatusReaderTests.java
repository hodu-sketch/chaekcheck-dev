package com.chaekcheck.auth.internal.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;

import javax.crypto.spec.SecretKeySpec;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import com.chaekcheck.auth.LoginStatus;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Login on, cycle 15 (ADR-0102, AC-25 reader part): only our HS256 signature decides; expiry does not. Keys are
 * random per run (test values).
 */
class LoginStatusReaderTests {

	private static final byte[] KEY = randomBytes(32);

	private final SignatureLoginStatusReader reader = new SignatureLoginStatusReader(new SecretKeySpec(KEY, "HmacSHA256"));

	@Test
	void noCookieIsSignedOut() {
		assertThat(reader.read(new MockHttpServletRequest())).isEqualTo(LoginStatus.SIGNED_OUT);
		assertThat(reader.read(withCookie(""))).isEqualTo(LoginStatus.SIGNED_OUT);
	}

	@Test
	void anotherKeyIsSignedOut() {
		assertThat(reader.read(withCookie(token(randomBytes(32), inMinutes(15))))).isEqualTo(LoginStatus.SIGNED_OUT);
	}

	@Test
	void brokenValueIsSignedOut() {
		assertThat(reader.read(withCookie("not-a-token"))).isEqualTo(LoginStatus.SIGNED_OUT);
		assertThat(reader.read(withCookie("a.b.c"))).isEqualTo(LoginStatus.SIGNED_OUT);
	}

	@Test
	void expiredButOursIsSignedIn() {
		assertThat(reader.read(withCookie(token(KEY, inMinutes(-60 * 24))))).isEqualTo(LoginStatus.SIGNED_IN);
	}

	@Test
	void validIsSignedIn() {
		assertThat(reader.read(withCookie(token(KEY, inMinutes(15))))).isEqualTo(LoginStatus.SIGNED_IN);
	}

	private static MockHttpServletRequest withCookie(String value) {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/books");
		request.setCookies(new Cookie("access_token", value));
		return request;
	}

	private static String token(byte[] key, Instant expiresAt) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
					new JWTClaimsSet.Builder().subject("7").expirationTime(Date.from(expiresAt)).build());
			jwt.sign(new MACSigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static Instant inMinutes(long minutes) {
		return Instant.now().plus(minutes, ChronoUnit.MINUTES);
	}

	private static byte[] randomBytes(int size) {
		byte[] bytes = new byte[size];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

}
