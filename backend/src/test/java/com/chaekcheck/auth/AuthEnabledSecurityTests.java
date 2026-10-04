package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Date;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Login on (step 3): only {@code /api/v1/me} needs a login, the token is read only from its cookie and only there,
 * CSRF guards every change, and the existing APIs answer as before (AC-17). Keys are random per run (test values).
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
@Transactional
class AuthEnabledSecurityTests {

	/** 64 bytes, so the same key can also sign the HS512 token that must be rejected. */
	static final byte[] KEY = randomBytes(64);

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Test
	void hasOneFilterChainAndNoGeneratedUser() {
		assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).hasSize(1);
		assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
	}

	@Test
	void meReturnsTheAccount() {
		long account = newAccount();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'KAKAO', '1234567890')")
			.param(account)
			.update();
		jdbc.sql("""
				INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
				VALUES (?, 'TERMS_OF_SERVICE', '2026-10-04', '2026-10-04T10:00:00Z'),
				       (?, 'AGE_OVER_14', NULL, '2026-10-04T10:00:01Z')""").params(account, account).update();

		assertThat(me(token(account, JWSAlgorithm.HS256, KEY, inMinutes(15)))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"id":%d,"loginMethods":["KAKAO"],"consents":[
					  {"type":"TERMS_OF_SERVICE","version":"2026-10-04","agreedAt":"2026-10-04T10:00:00Z"},
					  {"type":"AGE_OVER_14","agreedAt":"2026-10-04T10:00:01Z"}]}
					""".formatted(account));
	}

	@Test
	void expiredTokenIsUnauthorized() {
		long account = newAccount();

		// Past the 60-second clock skew the decoder allows
		assertUnauthorized(me(token(account, JWSAlgorithm.HS256, KEY, inMinutes(-5))));
	}

	@Test
	void tokenSignedWithAnotherKeyIsUnauthorized() {
		long account = newAccount();

		assertUnauthorized(me(token(account, JWSAlgorithm.HS256, randomBytes(32), inMinutes(15))));
	}

	@Test
	void tokenWithAnotherAlgorithmIsUnauthorized() {
		long account = newAccount();

		assertUnauthorized(me(token(account, JWSAlgorithm.HS512, KEY, inMinutes(15))));
		String unsigned = new PlainJWT(claims(String.valueOf(account), inMinutes(15))).serialize();
		assertUnauthorized(me(unsigned));
	}

	@Test
	void tokenOfDeletedAccountIsUnauthorized() {
		long account = newAccount();
		String token = token(account, JWSAlgorithm.HS256, KEY, inMinutes(15));
		jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(account).update();

		assertUnauthorized(me(token));
	}

	@Test
	void tokenWithoutNumericSubjectIsUnauthorized() {
		assertUnauthorized(me(sign(JWSAlgorithm.HS256, KEY, claims("someone", inMinutes(15)))));
	}

	@Test
	void tokenIsReadOnlyFromTheCookie() {
		long account = newAccount();
		String token = token(account, JWSAlgorithm.HS256, KEY, inMinutes(15));

		assertUnauthorized(mvc.get().uri("/api/v1/me").exchange());
		assertUnauthorized(mvc.get().uri("/api/v1/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token).exchange());
	}

	@Test
	void publicApiIgnoresBrokenTokens() {
		assertThat(mvc.get()
			.uri("/api/v1/regions")
			.cookie(new Cookie("access_token", "not-a-token"))
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")).hasStatusOk();
	}

	@Test
	void existingStreamIgnoresBrokenTokens() {
		// A book id with no holdings: the stream starts and ends without calling any library
		assertThat(mvc.get()
			.uri("/api/v1/book-availabilities/stream?bookIds=999999999")
			.cookie(new Cookie("access_token", "not-a-token"))
			.header(HttpHeaders.AUTHORIZATION, "Bearer not-a-token")).hasStatusOk()
			.hasContentTypeCompatibleWith("text/event-stream");
	}

	@Test
	void changeWithoutCsrfTokenIsForbidden() {
		assertThat(mvc.post().uri("/api/v1/auth/logout")).hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("csrf_invalid");
	}

	@Test
	void changeWithLoginCookieStillNeedsCsrfToken() {
		// The login cookie goes with every request on its path, so it must not stand in for the CSRF token
		long account = newAccount();
		String token = token(account, JWSAlgorithm.HS256, KEY, inMinutes(15));

		for (var request : java.util.List.of(mvc.post().uri("/api/v1/me"), mvc.delete().uri("/api/v1/me"),
				mvc.put().uri("/api/v1/me/settings"))) {
			assertThat(request.cookie(new Cookie("access_token", token))).hasStatus(HttpStatus.FORBIDDEN)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("csrf_invalid");
		}
	}

	@Test
	void csrfTokenEndpointGivesAReadableCookieThatPassesTheCheck() {
		MvcTestResult tokenResult = mvc.get().uri("/api/v1/auth/csrf-token").exchange();
		assertThat(tokenResult).hasStatus(HttpStatus.NO_CONTENT);
		String setCookie = tokenResult.getResponse().getHeader(HttpHeaders.SET_COOKIE);
		// SameSite is checked on a real server (AuthCookieHeaderTests): the mock response leaves it out
		assertThat(setCookie).startsWith("XSRF-TOKEN=").contains("Path=/;", "Secure").doesNotContainIgnoringCase("HttpOnly");
		String token = setCookie.substring("XSRF-TOKEN=".length(), setCookie.indexOf(';'));

		// Past the CSRF filter, Spring MVC answers: the regions API has no POST
		assertThat(mvc.post()
			.uri("/api/v1/regions")
			.cookie(new Cookie("XSRF-TOKEN", token))
			.header("X-XSRF-TOKEN", token)).hasStatus(HttpStatus.METHOD_NOT_ALLOWED);
	}

	@Test
	void webhooksNeedNoCsrfToken() {
		assertThat(mvc.post().uri("/api/v1/auth/webhooks/kakao-unlink").exchange().getResponse().getStatus())
			.isNotEqualTo(HttpStatus.FORBIDDEN.value());
		assertThat(mvc.post().uri("/api/v1/auth/webhooks/naver-unlink").exchange().getResponse().getStatus())
			.isNotEqualTo(HttpStatus.FORBIDDEN.value());
	}

	private MvcTestResult me(String token) {
		return mvc.get().uri("/api/v1/me").cookie(new Cookie("access_token", token)).exchange();
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"title":"Unauthorized","status":401,"detail":"Login required","code":"unauthorized"}
					""");
	}

	private long newAccount() {
		return jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
	}

	private static Instant inMinutes(long minutes) {
		return Instant.now().plus(minutes, ChronoUnit.MINUTES);
	}

	private static String token(long account, JWSAlgorithm algorithm, byte[] key, Instant expiresAt) {
		return sign(algorithm, key, claims(String.valueOf(account), expiresAt));
	}

	private static JWTClaimsSet claims(String subject, Instant expiresAt) {
		return new JWTClaimsSet.Builder().subject(subject).expirationTime(Date.from(expiresAt)).build();
	}

	private static String sign(JWSAlgorithm algorithm, byte[] key, JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(algorithm), claims);
			jwt.sign(new MACSigner(key));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static byte[] randomBytes(int size) {
		byte[] bytes = new byte[size];
		new SecureRandom().nextBytes(bytes);
		return bytes;
	}

}
