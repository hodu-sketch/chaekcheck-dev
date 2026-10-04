package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.auth.internal.login.PendingSignup;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Step 5 with fake Naver and Kakao endpoints (the JDK's own HTTP server, no extra test dependency). The providers'
 * read timeout is 1 second here so the timeout case runs fast; the real value is 5 seconds (C20).
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret", "chaekcheck.auth.provider-calls.read-timeout=1s" })
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class LoginCallbackTests {

	static final FakeProvider fake = FakeProvider.start();

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
		for (String provider : List.of("naver", "kakao")) {
			registry.add("chaekcheck.auth." + provider + ".authorization-uri", () -> fake.url("/" + provider + "/authorize"));
			registry.add("chaekcheck.auth." + provider + ".token-uri", () -> fake.url("/" + provider + "/token"));
			registry.add("chaekcheck.auth." + provider + ".user-info-uri", () -> fake.url("/" + provider + "/me"));
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

	@BeforeEach
	void resetFake() {
		fake.reset();
	}

	@Test
	void startRedirectsToTheProviderWithTheConfiguredRedirectUri() {
		MvcTestResult start = mvc.get().uri("/api/v1/auth/oauth2/authorization/kakao").exchange();

		assertThat(start).hasStatus(HttpStatus.FOUND);
		var location = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl()).build();
		assertThat(location.toUriString()).startsWith(fake.url("/kakao/authorize"));
		assertThat(location.getQueryParams().getFirst("redirect_uri"))
			.isEqualTo("http://localhost:5173/api/v1/auth/login/oauth2/code/kakao");
		assertThat(location.getQueryParams().getFirst("state")).isNotBlank();
		assertThat(location.getQueryParams()).doesNotContainKeys("scope", "code_challenge");
	}

	@Test
	void newKakaoAccountBecomesAPendingSignupWithoutDatabaseRows() {
		fake.kakaoUser = "{\"id\":4242424242,\"connected_at\":\"2026-10-04T00:00:00Z\"}";
		MockHttpSession session = new MockHttpSession();

		MvcTestResult callback = callback("kakao", session);

		assertThat(callback).hasStatus(HttpStatus.FOUND).hasHeader(HttpHeaders.LOCATION, "/signup");
		assertThat(callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).noneMatch(c -> c.startsWith("access_token"));
		PendingSignup pending = (PendingSignup) session.getAttribute(PendingSignup.SESSION_ATTRIBUTE);
		assertThat(pending).isNotNull();
		assertThat(pending.method()).isEqualTo("KAKAO");
		assertThat(pending.subject()).isEqualTo("4242424242");
		assertThat(pending.naverAccessToken()).isNull();
		assertThat(jdbc.sql("SELECT count(*) FROM auth.login_method WHERE subject = '4242424242'")
			.query(Long.class)
			.single()).isZero();
		assertNoLoginInSession(session);
	}

	@Test
	void newNaverAccountKeepsTheTokenOnlyForRevokingAndSendsStateAndSecret() {
		fake.naverUser = "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"naver-user-id-1\"}}";
		MockHttpSession session = new MockHttpSession();

		MvcTestResult callback = callback("naver", session);

		assertThat(callback).hasHeader(HttpHeaders.LOCATION, "/signup");
		PendingSignup pending = (PendingSignup) session.getAttribute(PendingSignup.SESSION_ATTRIBUTE);
		assertThat(pending.method()).isEqualTo("NAVER");
		assertThat(pending.subject()).isEqualTo("naver-user-id-1");
		assertThat(pending.naverAccessToken()).isEqualTo(FakeProvider.NAVER_ACCESS_TOKEN);
		assertThat(pending.toString()).doesNotContain(FakeProvider.NAVER_ACCESS_TOKEN);
		String tokenRequest = fake.tokenRequests.getLast();
		assertThat(tokenRequest).contains("client_id=test-naver-id", "client_secret=test-naver-secret", "state=",
				"grant_type=authorization_code", "code=test-code");
	}

	@Test
	void knownAccountSignsInAndUpdatesLastLogin() {
		long account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now() - interval '10 days') RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'KAKAO', '777')")
			.param(account)
			.update();
		Instant before = lastLogin(account);
		fake.kakaoUser = "{\"id\":777}";
		MockHttpSession session = new MockHttpSession();

		MvcTestResult callback = callback("kakao", session);

		assertThat(callback).hasStatus(HttpStatus.FOUND)
			.hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=login_success");
		assertThat(callback.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
			.anyMatch(c -> c.startsWith("access_token=") && c.contains("Path=/api/v1/me"))
			.anyMatch(c -> c.startsWith("refresh_token=") && c.contains("Path=/api/v1/auth"));
		assertThat(lastLogin(account)).isAfter(before);
		assertThat(session.getAttribute(PendingSignup.SESSION_ATTRIBUTE)).isNull();
		assertNoLoginInSession(session);
	}

	@Test
	void cancelAtTheProviderIsLoginCancelled() {
		MockHttpSession session = new MockHttpSession();
		String state = start("kakao", session);

		MvcTestResult callback = mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/kakao")
			.param("error", "access_denied")
			.param("error_description", "User denied access")
			.param("state", state)
			.session(session)
			.exchange();

		assertThat(callback).hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=login_cancelled");
	}

	@Test
	void callbackWithoutASavedRequestIsLoginExpired() {
		MvcTestResult callback = mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/kakao?code=test-code&state=anything")
			.session(new MockHttpSession())
			.exchange();

		assertThat(callback).hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=login_expired");
	}

	@Test
	void callbackWithAnotherStateIsLoginStateMismatch() {
		MockHttpSession session = new MockHttpSession();
		start("naver", session);

		MvcTestResult callback = mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/naver?code=test-code&state=not-the-saved-state")
			.session(session)
			.exchange();

		assertThat(callback).hasHeader(HttpHeaders.LOCATION, "/auth/complete?result=login_state_mismatch");
	}

	@Test
	void tokenExchangeFailureIsProviderErrorWithTheProvider() {
		fake.tokenStatus = 500;

		MvcTestResult callback = callback("kakao", new MockHttpSession());

		assertThat(callback).hasHeader(HttpHeaders.LOCATION,
				"/auth/complete?result=login_provider_error&provider=kakao");
	}

	@Test
	void slowProfileApiTimesOutAsProviderError() {
		fake.profileDelayMillis = 2_000;

		MvcTestResult callback = callback("naver", new MockHttpSession());

		assertThat(callback).hasHeader(HttpHeaders.LOCATION,
				"/auth/complete?result=login_provider_error&provider=naver");
	}

	@Test
	void providerTokensAreNotLogged(CapturedOutput output) {
		fake.naverUser = "{\"resultcode\":\"00\",\"response\":{\"id\":\"naver-user-id-2\"}}";
		callback("naver", new MockHttpSession());
		fake.tokenStatus = 500;
		callback("kakao", new MockHttpSession());

		assertThat(output.getAll()).doesNotContain(FakeProvider.NAVER_ACCESS_TOKEN, FakeProvider.KAKAO_ACCESS_TOKEN,
				"test-naver-secret", "test-kakao-secret");
	}

	@Test
	void unknownProviderIsNotFound() {
		for (String id : List.of("google", "naver-reauth", "kakao-reauth")) {
			assertThat(mvc.get().uri("/api/v1/auth/oauth2/authorization/" + id)).hasStatus(HttpStatus.NOT_FOUND)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("not_found");
		}
	}

	@Test
	void springLoginPageIsNotGenerated() {
		assertThat(mvc.get().uri("/login")).hasStatus(HttpStatus.NOT_FOUND);
	}

	/** Starts the login and returns the {@code state} the provider would send back. */
	private String start(String provider, MockHttpSession session) {
		MvcTestResult start = mvc.get().uri("/api/v1/auth/oauth2/authorization/" + provider).session(session).exchange();
		String encoded = UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
			.build()
			.getQueryParams()
			.getFirst("state");
		return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
	}

	private MvcTestResult callback(String provider, MockHttpSession session) {
		String state = start(provider, session);
		return mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/" + provider)
			.param("code", "test-code")
			.param("state", state)
			.session(session)
			.exchange();
	}

	private static void assertNoLoginInSession(MockHttpSession session) {
		assertThat(java.util.Collections.list(session.getAttributeNames()))
			.noneMatch(name -> name.contains("SPRING_SECURITY_CONTEXT"));
	}

	private Instant lastLogin(long account) {
		return jdbc.sql("SELECT last_login_at FROM auth.account WHERE id = ?")
			.param(account)
			.query((rs, n) -> rs.getObject(1, OffsetDateTime.class).toInstant())
			.single();
	}

	/** Token and profile endpoints of both providers, answering what the current test sets. */
	static final class FakeProvider {

		static final String NAVER_ACCESS_TOKEN = "fake-naver-access-token-5b1f";

		static final String KAKAO_ACCESS_TOKEN = "fake-kakao-access-token-9c2e";

		final HttpServer server;

		final List<String> tokenRequests = new CopyOnWriteArrayList<>();

		volatile int tokenStatus;

		volatile long profileDelayMillis;

		volatile String naverUser;

		volatile String kakaoUser;

		private FakeProvider(HttpServer server) {
			this.server = server;
			reset();
		}

		static FakeProvider start() {
			try {
				HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
				FakeProvider fake = new FakeProvider(server);
				server.createContext("/naver/token", exchange -> fake.token(exchange, NAVER_ACCESS_TOKEN));
				server.createContext("/kakao/token", exchange -> fake.token(exchange, KAKAO_ACCESS_TOKEN));
				server.createContext("/naver/me", exchange -> fake.profile(exchange, fake.naverUser));
				server.createContext("/kakao/me", exchange -> fake.profile(exchange, fake.kakaoUser));
				server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
				server.start();
				return fake;
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
		}

		void reset() {
			tokenStatus = 200;
			profileDelayMillis = 0;
			naverUser = "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"naver-default\"}}";
			kakaoUser = "{\"id\":1}";
			tokenRequests.clear();
		}

		String url(String path) {
			return "http://127.0.0.1:" + server.getAddress().getPort() + path;
		}

		private void token(HttpExchange exchange, String accessToken) throws IOException {
			tokenRequests.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),
					StandardCharsets.UTF_8), StandardCharsets.UTF_8));
			if (tokenStatus != 200) {
				send(exchange, tokenStatus, "{\"error\":\"server_error\"}");
				return;
			}
			send(exchange, 200, "{\"access_token\":\"" + accessToken
					+ "\",\"token_type\":\"bearer\",\"expires_in\":3600,\"refresh_token\":\"fake-provider-refresh\"}");
		}

		private void profile(HttpExchange exchange, String body) throws IOException {
			if (profileDelayMillis > 0) {
				try {
					Thread.sleep(profileDelayMillis);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			send(exchange, 200, body);
		}

		private static void send(HttpExchange exchange, int status, String body) throws IOException {
			byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
			exchange.sendResponseHeaders(status, bytes.length);
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
			catch (IOException ex) {
				// the client gave up (timeout case)
			}
		}

	}

}
