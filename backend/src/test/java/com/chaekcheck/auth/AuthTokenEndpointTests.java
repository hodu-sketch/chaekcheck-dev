package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;
import com.nimbusds.jwt.SignedJWT;

/** Step 4: issuing, refresh rotation (C10), logout and the expired-row clean-up, with login on. */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
@Transactional
class AuthTokenEndpointTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	static final String CSRF = "test-csrf-token";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TokenService tokens;

	@Test
	void signInIssuesBothCookiesAndAJwtWithOnlySubAndExp() throws Exception {
		long account = newAccount();
		LoginCookies cookies = tokens.signIn(account);

		assertThat(cookies.access().toString()).startsWith("access_token=")
			.contains("Path=/api/v1;", "Max-Age=900", "Secure", "HttpOnly", "SameSite=Lax")
			.doesNotContain("Domain");
		assertThat(cookies.refresh().toString()).startsWith("refresh_token=")
			.contains("Path=/api/v1/auth", "Secure", "HttpOnly", "SameSite=Lax")
			.doesNotContain("Domain");
		assertThat(cookies.refresh().getMaxAge().toDays()).isBetween(29L, 30L);

		Map<String, Object> claims = SignedJWT.parse(cookies.access().getValue()).getJWTClaimsSet().getClaims();
		assertThat(claims).containsOnlyKeys("sub", "exp").containsEntry("sub", String.valueOf(account));
		assertThat(row(cookies.refresh().getValue())).isNotNull();

		// The issued access token opens the account API
		assertThat(mvc.get().uri("/api/v1/me").cookie(new Cookie("access_token", cookies.access().getValue())))
			.hasStatusOk();
	}

	@Test
	void refreshRotatesTheValueAndKeepsTheExpiry() {
		long account = newAccount();
		String first = tokens.signIn(account).refresh().getValue();
		Row before = row(first);

		MvcTestResult result = refresh(first);

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		Map<String, String> set = setCookies(result);
		String second = set.get("refresh_token");
		assertThat(second).isNotBlank().isNotEqualTo(first);
		assertThat(set.get("access_token")).isNotBlank();
		Row after = row(second);
		assertThat(after.id()).isEqualTo(before.id());
		assertThat(after.previousTokenHash()).isEqualTo(hash(first));
		assertThat(after.rotatedAt()).isNotNull();
		assertThat(after.expiresAt()).isEqualTo(before.expiresAt());
	}

	@Test
	void reusedValueEndsTheLogin() {
		long account = newAccount();
		String first = tokens.signIn(account).refresh().getValue();
		assertThat(refresh(first)).hasStatus(HttpStatus.NO_CONTENT);

		MvcTestResult reused = refresh(first);

		assertUnauthorizedAndCleared(reused);
		assertThat(rowCount(account)).isZero();
	}

	@Test
	void unknownValueIsUnauthorizedAndDeletesNothing() {
		long account = newAccount();
		tokens.signIn(account);

		assertUnauthorizedAndCleared(refresh("not-a-known-value"));
		assertUnauthorizedAndCleared(mvc.post()
			.uri("/api/v1/auth/refresh")
			.cookie(new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)
			.exchange());
		assertThat(rowCount(account)).isEqualTo(1);
	}

	@Test
	void expiredValueIsUnauthorized() {
		long account = newAccount();
		String value = tokens.signIn(account).refresh().getValue();
		jdbc.sql("UPDATE auth.refresh_token SET expires_at = now() - interval '1 second' WHERE account_id = ?")
			.param(account)
			.update();

		assertUnauthorizedAndCleared(refresh(value));
	}

	@Test
	void logoutDeletesTheRowAndClearsCookiesWithTheSameAttributes() {
		long account = newAccount();
		LoginCookies cookies = tokens.signIn(account);

		MvcTestResult result = mvc.post()
			.uri("/api/v1/auth/logout")
			.cookie(new Cookie("refresh_token", cookies.refresh().getValue()), new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)
			.exchange();

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(rowCount(account)).isZero();
		// Cycle 15 (ADR-0102): the access cookie lives on /api/v1 and is cleared there
		assertThat(cookies.access().getPath()).isEqualTo("/api/v1");
		assertClearedLike(result, cookies);
	}

	@Test
	void logoutWithoutLoginIsStillNoContent() {
		assertThat(mvc.post()
			.uri("/api/v1/auth/logout")
			.cookie(new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)).hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void refreshAndLogoutNeedTheCsrfToken() {
		long account = newAccount();
		String value = tokens.signIn(account).refresh().getValue();

		for (String path : List.of("/api/v1/auth/refresh", "/api/v1/auth/logout")) {
			assertThat(mvc.post().uri(path).cookie(new Cookie("refresh_token", value)))
				.hasStatus(HttpStatus.FORBIDDEN)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("csrf_invalid");
		}
		assertThat(rowCount(account)).isEqualTo(1);
	}

	@Test
	void cleanupDeletesOnlyExpiredRows() throws Exception {
		long account = newAccount();
		tokens.signIn(account);
		tokens.signIn(account);
		jdbc.sql("""
				UPDATE auth.refresh_token SET expires_at = now() - interval '1 minute'
				WHERE id = (SELECT min(id) FROM auth.refresh_token WHERE account_id = ?)""").param(account).update();

		var method = TokenService.class.getDeclaredMethod("deleteExpired");
		method.setAccessible(true);
		assertThat((int) method.invoke(tokens)).isGreaterThanOrEqualTo(1);

		assertThat(rowCount(account)).isEqualTo(1);
	}

	private MvcTestResult refresh(String value) {
		return mvc.post()
			.uri("/api/v1/auth/refresh")
			.cookie(new Cookie("refresh_token", value), new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)
			.exchange();
	}

	private static void assertUnauthorizedAndCleared(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("unauthorized");
		Map<String, String> set = setCookies(result);
		assertThat(set).containsEntry("access_token", "").containsEntry("refresh_token", "");
	}

	/** Each cleared cookie has the name, path and attributes it was issued with, and Max-Age=0. */
	private static void assertClearedLike(MvcTestResult result, LoginCookies issued) {
		List<String> headers = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
		for (ResponseCookie cookie : List.of(issued.access(), issued.refresh())) {
			assertThat(headers).filteredOn(header -> header.startsWith(cookie.getName() + "=;"))
				.singleElement()
				.satisfies(header -> assertThat(header).contains("Path=" + cookie.getPath() + ";", "Max-Age=0", "Secure",
						"HttpOnly", "SameSite=" + cookie.getSameSite())
					.doesNotContain("Domain"));
		}
	}

	private static Map<String, String> setCookies(MvcTestResult result) {
		Map<String, String> cookies = new java.util.HashMap<>();
		for (String header : result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)) {
			String pair = header.substring(0, header.indexOf(';'));
			cookies.put(pair.substring(0, pair.indexOf('=')), pair.substring(pair.indexOf('=') + 1));
		}
		return cookies;
	}

	private long newAccount() {
		return jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
	}

	private long rowCount(long account) {
		return jdbc.sql("SELECT count(*) FROM auth.refresh_token WHERE account_id = ?")
			.param(account)
			.query(Long.class)
			.single();
	}

	private Row row(String value) {
		return jdbc.sql("""
				SELECT id, previous_token_hash, expires_at, rotated_at FROM auth.refresh_token WHERE token_hash = ?""")
			.param(hash(value))
			.query((rs, n) -> new Row(rs.getLong(1), rs.getBytes(2), rs.getObject(3, OffsetDateTime.class).toInstant(),
					rs.getObject(4, OffsetDateTime.class) == null ? null
							: rs.getObject(4, OffsetDateTime.class).toInstant()))
			.optional()
			.orElse(null);
	}

	static byte[] hash(String value) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	record Row(long id, byte[] previousTokenHash, Instant expiresAt, Instant rotatedAt) {
	}

}
