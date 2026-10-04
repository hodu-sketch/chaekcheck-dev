package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionListener;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MockMvcTester.MockMvcRequestBuilder;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.internal.login.PendingSignup;
import com.nimbusds.jwt.SignedJWT;

/** Step 6: consent and sign-up, cancel, and abandoned sign-ups, with fake Naver and Kakao unlink endpoints. */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret",
		"chaekcheck.auth.documents.terms-of-service-version=2026-10-01",
		"chaekcheck.auth.documents.privacy-collection-version=2026-10-02" })
@AutoConfigureMockMvc
@Transactional
class SignupTests {

	static final FakeProvider fake = FakeProvider.start();

	static final String CSRF = "test-csrf-token";

	static final String VALID = """
			{"consents":[{"type":"TERMS_OF_SERVICE","version":"2026-10-01"},
			  {"type":"PRIVACY_COLLECTION","version":"2026-10-02"},{"type":"AGE_OVER_14"}]}""";

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
		for (String provider : List.of("naver", "kakao")) {
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
	ApplicationContext context;

	@BeforeEach
	void resetFake() {
		fake.reset();
	}

	@Test
	void signupInfoShowsTheProviderAndCurrentVersions() {
		assertThat(mvc.get().uri("/api/v1/auth/signup").session(pending("KAKAO", "s-info", null))).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("""
					{"provider":"KAKAO","documents":[{"type":"TERMS_OF_SERVICE","version":"2026-10-01"},
					  {"type":"PRIVACY_COLLECTION","version":"2026-10-02"}]}""");
	}

	@Test
	void withoutPendingSignupEverythingIsNotFound() {
		for (MvcTestResult result : List.of(mvc.get().uri("/api/v1/auth/signup").exchange(),
				withCsrf(mvc.post().uri("/api/v1/auth/signup")).contentType(MediaType.APPLICATION_JSON)
					.content(VALID)
					.exchange(),
				withCsrf(mvc.delete().uri("/api/v1/auth/signup")).exchange())) {
			assertThat(result).hasStatus(HttpStatus.NOT_FOUND)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("signup_not_found");
		}
	}

	@Test
	void signupCreatesTheRowsAndSignsIn() throws Exception {
		MockHttpSession session = pending("KAKAO", "s-new", null);

		MvcTestResult result = signUp(session, VALID);

		assertThat(result).hasStatus(HttpStatus.CREATED).doesNotContainHeader(HttpHeaders.LOCATION);
		long account = accountOf("KAKAO", "s-new");
		assertThat(count("auth.account WHERE id = " + account)).isEqualTo(1);
		assertThat(count("auth.login_method WHERE account_id = " + account)).isEqualTo(1);
		assertThat(count("auth.refresh_token WHERE account_id = " + account)).isEqualTo(1);
		assertThat(jdbc.sql("""
				SELECT consent_type || ':' || coalesce(document_version, '-') FROM auth.account_consent
				WHERE account_id = ? ORDER BY consent_type""").param(account).query(String.class).list())
			.containsExactly("AGE_OVER_14:-", "PRIVACY_COLLECTION:2026-10-02", "TERMS_OF_SERVICE:2026-10-01");
		assertThat(subjectOfAccessCookie(result)).isEqualTo(String.valueOf(account));
		assertThat(session.getAttribute(PendingSignup.SESSION_ATTRIBUTE)).isNull();
		assertThat(fake.unlinkRequests).isEmpty();
	}

	@Test
	void missingConsentIsValidationError() {
		MockHttpSession session = pending("KAKAO", "s-missing", null);

		MvcTestResult result = signUp(session, """
				{"consents":[{"type":"TERMS_OF_SERVICE","version":"2026-10-01"},
				  {"type":"PRIVACY_COLLECTION","version":"2026-10-02"}]}""");

		assertThat(result).hasStatus(HttpStatus.BAD_REQUEST).bodyJson().isLenientlyEqualTo("""
				{"code":"validation_error","errors":[{"field":"consents","code":"required",
				  "message":"AGE_OVER_14 is required"}]}""");
		assertThat(count("auth.login_method WHERE subject = 's-missing'")).isZero();
		assertThat(session.getAttribute(PendingSignup.SESSION_ATTRIBUTE)).isNotNull();
	}

	@Test
	void oldDocumentVersionIsConflict() {
		MockHttpSession session = pending("NAVER", "s-old", "naver-token");

		MvcTestResult result = signUp(session, VALID.replace("2026-10-02", "2026-09-01"));

		assertThat(result).hasStatus(HttpStatus.CONFLICT)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("document_version_mismatch");
		assertThat(count("auth.login_method WHERE subject = 's-old'")).isZero();
	}

	@Test
	void secondSignupWithTheSameProviderNumberSignsInToTheFirstAccount() throws Exception {
		MvcTestResult first = signUp(pending("KAKAO", "s-twice", null), VALID);
		MvcTestResult second = signUp(pending("KAKAO", "s-twice", null), VALID);

		assertThat(first).hasStatus(HttpStatus.CREATED);
		assertThat(second).hasStatus(HttpStatus.CREATED);
		long account = accountOf("KAKAO", "s-twice");
		assertThat(subjectOfAccessCookie(second)).isEqualTo(String.valueOf(account));
		assertThat(count("auth.login_method WHERE subject = 's-twice'")).isEqualTo(1);
		assertThat(count("auth.account_consent WHERE account_id = " + account)).isEqualTo(3);
		assertThat(count("auth.refresh_token WHERE account_id = " + account)).isEqualTo(2);
	}

	@Test
	void signupNeedsTheCsrfToken() {
		assertThat(mvc.post()
			.uri("/api/v1/auth/signup")
			.session(pending("KAKAO", "s-csrf", null))
			.contentType(MediaType.APPLICATION_JSON)
			.content(VALID)).hasStatus(HttpStatus.FORBIDDEN).bodyJson().extractingPath("$.code").isEqualTo("csrf_invalid");
	}

	@Test
	void cancelUnlinksKakaoOnceWithTheAdminKey() {
		MockHttpSession session = pending("KAKAO", "4242", null);

		assertThat(withCsrf(mvc.delete().uri("/api/v1/auth/signup")).session(session))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(fake.unlinkRequests).singleElement()
			.isEqualTo("/kakao/unlink|KakaoAK test-kakao-admin-key|target_id_type=user_id&target_id=4242");
		assertThat(session.getAttribute(PendingSignup.SESSION_ATTRIBUTE)).isNull();
		assertThat(count("auth.login_method WHERE subject = '4242'")).isZero();
	}

	@Test
	void cancelRevokesTheNaverTokenOnce() {
		assertThat(withCsrf(mvc.delete().uri("/api/v1/auth/signup")).session(pending("NAVER", "n-1", "naver-token-1")))
			.hasStatus(HttpStatus.NO_CONTENT);

		assertThat(fake.unlinkRequests).singleElement()
			.isEqualTo("/naver/unlink|null|client_id=test-naver-id&client_secret=test-naver-secret&token=naver-token-1"
					+ "&token_type_hint=access_token");
	}

	@Test
	void cancelIsNoContentEvenWhenTheProviderFails() {
		fake.unlinkStatus = 500;

		assertThat(withCsrf(mvc.delete().uri("/api/v1/auth/signup")).session(pending("KAKAO", "s-fail", null)))
			.hasStatus(HttpStatus.NO_CONTENT);
		assertThat(fake.unlinkRequests).hasSize(1);
	}

	@Test
	void expiredSessionWithPendingSignupUnlinksAndWithoutDoesNot() {
		HttpSessionListener listener = context.getBeansOfType(HttpSessionListener.class)
			.values()
			.stream()
			.filter(bean -> bean.getClass().getSimpleName().equals("AbandonedSignupListener"))
			.findFirst()
			.orElseThrow();

		listener.sessionDestroyed(new HttpSessionEvent(new MockHttpSession()));
		assertThat(fake.unlinkRequests).isEmpty();

		listener.sessionDestroyed(new HttpSessionEvent(pending("KAKAO", "9999", null)));
		assertThat(fake.unlinkRequests).singleElement().asString().contains("target_id=9999");
	}

	private MvcTestResult signUp(MockHttpSession session, String body) {
		return withCsrf(mvc.post().uri("/api/v1/auth/signup")).session(session)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

	private static MockMvcRequestBuilder withCsrf(MockMvcRequestBuilder request) {
		return request.cookie(new Cookie("XSRF-TOKEN", CSRF)).header("X-XSRF-TOKEN", CSRF);
	}

	private static MockHttpSession pending(String method, String subject, String naverToken) {
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(PendingSignup.SESSION_ATTRIBUTE,
				new PendingSignup(method, subject, Instant.now(), naverToken));
		return session;
	}

	private long accountOf(String method, String subject) {
		return jdbc.sql("SELECT account_id FROM auth.login_method WHERE method = ? AND subject = ?")
			.params(method, subject)
			.query(Long.class)
			.single();
	}

	private long count(String fromWhere) {
		return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
	}

	private static String subjectOfAccessCookie(MvcTestResult result) throws Exception {
		String header = result.getResponse()
			.getHeaders(HttpHeaders.SET_COOKIE)
			.stream()
			.filter(cookie -> cookie.startsWith("access_token="))
			.findFirst()
			.orElseThrow();
		String jwt = header.substring("access_token=".length(), header.indexOf(';'));
		return SignedJWT.parse(jwt).getJWTClaimsSet().getSubject();
	}

}
