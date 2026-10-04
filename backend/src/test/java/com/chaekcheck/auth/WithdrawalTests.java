package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.login.ReauthenticationProof;
import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;

/** Step 8: withdrawal re-authentication and {@code DELETE /api/v1/me}, with fake Naver and Kakao endpoints. */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
@Transactional
class WithdrawalTests {

	static final FakeProvider fake = FakeProvider.start();

	static final String CSRF = "test-csrf-token";

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
		for (String provider : List.of("naver", "kakao")) {
			registry.add("chaekcheck.auth." + provider + ".authorization-uri", () -> fake.url("/" + provider + "/authorize"));
			registry.add("chaekcheck.auth." + provider + ".token-uri", () -> fake.url("/" + provider + "/token"));
			registry.add("chaekcheck.auth." + provider + ".user-info-uri", () -> fake.url("/" + provider + "/me"));
			registry.add("chaekcheck.auth." + provider + ".unlink-uri", () -> fake.url("/" + provider + "/unlink"));
		}
	}

	@AfterAll
	static void stopFake() {
		fake.server.stop(0);
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TokenService tokens;

	@BeforeEach
	void resetFake() {
		fake.reset();
	}

	@Test
	void reauthenticationAsksTheProviderToSignInAgain() {
		var naver = startLocation("naver-reauth", new MockHttpSession());
		var kakao = startLocation("kakao-reauth", new MockHttpSession());

		assertThat(naver.getQueryParams().getFirst("auth_type")).isEqualTo("reauthenticate");
		assertThat(naver.getQueryParams().getFirst("redirect_uri"))
			.isEqualTo("http://localhost:5173/api/v1/auth/login/oauth2/code/naver-reauth");
		assertThat(kakao.getQueryParams().getFirst("prompt")).isEqualTo("login");
		assertThat(kakao.getQueryParams().getFirst("redirect_uri"))
			.isEqualTo("http://localhost:5173/api/v1/auth/login/oauth2/code/kakao-reauth");
		// Plain login asks for neither
		var login = startLocation("kakao", new MockHttpSession());
		assertThat(login.getQueryParams()).doesNotContainKeys("prompt", "auth_type");
	}

	@Test
	void sameAccountGetsAProofWithoutNewCookiesOrRows() {
		long account = account("KAKAO", "555");
		String refresh = tokens.signIn(account).refresh().getValue();
		fake.kakaoUser = "{\"id\":555}";
		MockHttpSession session = new MockHttpSession();

		MvcTestResult callback = reauthCallback("kakao-reauth", session, refresh);

		assertThat(callback).hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=withdraw_reauthenticated");
		// Spring rotates the CSRF token on authentication; no login cookie is issued
		assertThat(callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.noneMatch(c -> c.startsWith("access_token") || c.startsWith("refresh_token"));
		ReauthenticationProof proof = (ReauthenticationProof) session.getAttribute(ReauthenticationProof.SESSION_ATTRIBUTE);
		assertThat(proof.accountId()).isEqualTo(account);
		assertThat(proof.method()).isEqualTo("KAKAO");
		assertThat(refreshRows(account)).isEqualTo(1);
	}

	@Test
	void anotherProviderAccountIsAccountMismatch() {
		long account = account("KAKAO", "555");
		account("KAKAO", "666");
		String refresh = tokens.signIn(account).refresh().getValue();
		fake.kakaoUser = "{\"id\":666}";
		MockHttpSession session = new MockHttpSession();

		assertThat(reauthCallback("kakao-reauth", session, refresh)).hasHeader(HttpHeaders.LOCATION,
				"/auth/complete?result=withdraw_account_mismatch");
		assertThat(session.getAttribute(ReauthenticationProof.SESSION_ATTRIBUTE)).isNull();
	}

	@Test
	void withoutALiveRefreshCookieIsLoginExpired() {
		account("KAKAO", "555");
		fake.kakaoUser = "{\"id\":555}";

		assertThat(reauthCallback("kakao-reauth", new MockHttpSession(), null)).hasHeader(HttpHeaders.LOCATION,
				"/auth/complete?result=withdraw_login_expired");
		assertThat(reauthCallback("kakao-reauth", new MockHttpSession(), "unknown-value"))
			.hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=withdraw_login_expired");
	}

	@Test
	void cancelAndProviderErrorHaveWithdrawalCodes() {
		MockHttpSession session = new MockHttpSession();
		String state = state(startLocation("naver-reauth", session));
		assertThat(mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/naver-reauth")
			.param("error", "access_denied")
			.param("state", state)
			.session(session)).hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=withdraw_cancelled");

		fake.tokenStatus = 500;
		assertThat(reauthCallback("kakao-reauth", new MockHttpSession(), null)).hasHeader(HttpHeaders.LOCATION,
				"/auth/complete?result=withdraw_provider_error&provider=kakao");
	}

	@Test
	void withdrawalWithoutAProofIsRequired() {
		long account = account("KAKAO", "555");
		LoginCookies cookies = tokens.signIn(account);

		assertForbidden(withdraw(cookies, new MockHttpSession()), "reauthentication_required");
		assertForbidden(withdraw(cookies, proof(account + 1, "KAKAO", Instant.now(), null)), "reauthentication_required");
		assertThat(accountExists(account)).isTrue();
	}

	@Test
	void proofCountsForFiveMinutes() {
		long account = account("KAKAO", "555");
		LoginCookies cookies = tokens.signIn(account);

		assertForbidden(withdraw(cookies, proof(account, "KAKAO", Instant.now().minus(Duration.ofMinutes(5)).minusSeconds(1),
				null)), "reauthentication_expired");
		assertThat(accountExists(account)).isTrue();

		assertThat(withdraw(cookies, proof(account, "KAKAO", Instant.now().minus(Duration.ofMinutes(4)).minusSeconds(50),
				null))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(accountExists(account)).isFalse();
	}

	@Test
	void kakaoWithdrawalUnlinksOnceDeletesAndEndsEveryLogin() {
		long account = account("KAKAO", "555");
		LoginCookies here = tokens.signIn(account);
		LoginCookies otherBrowser = tokens.signIn(account);
		MockHttpSession session = proof(account, "KAKAO", Instant.now(), null);

		MvcTestResult result = withdraw(here, session);

		assertThat(result).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(fake.unlinkRequests).singleElement()
			.isEqualTo("/kakao/unlink|KakaoAK test-kakao-admin-key|target_id_type=user_id&target_id=555");
		assertThat(accountExists(account)).isFalse();
		assertThat(session.getAttribute(ReauthenticationProof.SESSION_ATTRIBUTE)).isNull();
		assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.anyMatch(c -> c.startsWith("access_token=;") && c.contains("Path=/api/v1/me;") && c.contains("Max-Age=0"))
			.anyMatch(c -> c.startsWith("refresh_token=;") && c.contains("Path=/api/v1/auth;") && c.contains("Max-Age=0"));
		// The other browser (AC-9)
		assertThat(mvc.get().uri("/api/v1/me").cookie(new Cookie("access_token", otherBrowser.access().getValue())))
			.hasStatus(HttpStatus.UNAUTHORIZED);
		assertThat(withCsrf(mvc.post().uri("/api/v1/auth/refresh"))
			.cookie(new Cookie("refresh_token", otherBrowser.refresh().getValue()), new Cookie("XSRF-TOKEN", CSRF)))
			.hasStatus(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void proofIsUsedOnceAndPendingSignupGoesWithIt() {
		long account = account("KAKAO", "555");
		LoginCookies cookies = tokens.signIn(account);
		MockHttpSession session = proof(account, "KAKAO", Instant.now(), null);
		session.setAttribute(PendingSignup.SESSION_ATTRIBUTE,
				new PendingSignup("KAKAO", "555", Instant.now(), null));

		assertThat(withdraw(cookies, session)).hasStatus(HttpStatus.NO_CONTENT);

		assertThat(session.getAttribute(PendingSignup.SESSION_ATTRIBUTE)).isNull();
		// A second request with the same session has no proof any more
		long other = account("KAKAO", "556");
		assertForbidden(withdraw(tokens.signIn(other), session), "reauthentication_required");
		assertThat(fake.unlinkRequests).hasSize(1);
	}

	@Test
	void naverWithdrawalRevokesTheTokenFromTheReauthentication() {
		long account = account("NAVER", "naver-555");
		LoginCookies cookies = tokens.signIn(account);

		assertThat(withdraw(cookies, proof(account, "NAVER", Instant.now(), "naver-reauth-token")))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(fake.unlinkRequests).singleElement().asString().startsWith("/naver/unlink|").contains("token=naver-reauth-token");
	}

	@Test
	void providerFailureStillWithdraws() {
		fake.unlinkStatus = 500;
		long account = account("KAKAO", "555");

		assertThat(withdraw(tokens.signIn(account), proof(account, "KAKAO", Instant.now(), null)))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(fake.unlinkRequests).hasSize(1);
		assertThat(accountExists(account)).isFalse();
	}

	@Test
	void withdrawalNeedsTheCsrfToken() {
		long account = account("KAKAO", "555");
		LoginCookies cookies = tokens.signIn(account);

		assertThat(mvc.delete()
			.uri("/api/v1/me")
			.session(proof(account, "KAKAO", Instant.now(), null))
			.cookie(new Cookie("access_token", cookies.access().getValue())))
			.hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("csrf_invalid");
		assertThat(accountExists(account)).isTrue();
	}

	private MvcTestResult withdraw(LoginCookies cookies, MockHttpSession session) {
		return withCsrf(mvc.delete().uri("/api/v1/me")).session(session)
			.cookie(new Cookie("access_token", cookies.access().getValue()), new Cookie("XSRF-TOKEN", CSRF))
			.exchange();
	}

	private MvcTestResult reauthCallback(String registrationId, MockHttpSession session, String refresh) {
		String state = state(startLocation(registrationId, session));
		MockMvcRequestBuilder callback = mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/" + registrationId)
			.param("code", "test-code")
			.param("state", state)
			.session(session);
		if (refresh != null) {
			callback = callback.cookie(new Cookie("refresh_token", refresh));
		}
		return callback.exchange();
	}

	private org.springframework.web.util.UriComponents startLocation(String registrationId, MockHttpSession session) {
		MvcTestResult start = mvc.get().uri("/api/v1/auth/oauth2/authorization/" + registrationId).session(session).exchange();
		assertThat(start).hasStatus(HttpStatus.FOUND);
		return UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build();
	}

	private static String state(org.springframework.web.util.UriComponents location) {
		return URLDecoder.decode(location.getQueryParams().getFirst("state"), StandardCharsets.UTF_8);
	}

	private static MockMvcRequestBuilder withCsrf(MockMvcRequestBuilder request) {
		return request.header("X-XSRF-TOKEN", CSRF);
	}

	private static MockHttpSession proof(long account, String method, Instant verifiedAt, String naverToken) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(ReauthenticationProof.SESSION_ATTRIBUTE,
				new ReauthenticationProof(account, method, verifiedAt, naverToken));
		return session;
	}

	private static void assertForbidden(MvcTestResult result, String code) {
		assertThat(result).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code").isEqualTo(code);
	}

	private long account(String method, String subject) {
		long id = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, ?, ?)")
			.params(id, method, subject)
			.update();
		return id;
	}

	private boolean accountExists(long account) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.account WHERE id = ?)").param(account).query(Boolean.class).single();
	}

	private long refreshRows(long account) {
		return jdbc.sql("SELECT count(*) FROM auth.refresh_token WHERE account_id = ?").param(account).query(Long.class).single();
	}

}
