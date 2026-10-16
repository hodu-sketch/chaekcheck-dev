package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import jakarta.servlet.http.Cookie;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Login on and logging on (cycle 15). AC-9 on part: {@code POST /api/v1/usage-events} needs the CSRF token but no
 * login; the existing CSRF setup (every change except the webhooks) already covers it, no security change was needed.
 * AC-20/AC-25 on part: the login status of a search row comes from the cookie's signature only, and the answer is the
 * same for every cookie. The login-off part is {@code CatalogUsageLogTests} ({@code login_off}).
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.public-base-url=http://localhost:5173",
		"chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret",
		"chaekcheck.usagelog.enabled=true", "chaekcheck.usagelog.flush-interval=1h" })
@AutoConfigureMockMvc
@Transactional
// its own context (login on and logging on): closed after the class so db-test keeps enough connections
@DirtiesContext
class UsageEventCsrfTests {

	static final String CSRF = "test-csrf-token";

	static final String BODY = "{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"11\"}";

	static final byte[] KEY = randomKey();

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UsageLogFlusher flusher;

	@BeforeEach
	void empty() {
		UsageLogTestTables.empty(jdbc);
	}

	@Test
	void withoutTheCsrfTokenIsForbidden() {
		assertThat(mvc.post().uri("/api/v1/usage-events").contentType(MediaType.APPLICATION_JSON).content(BODY))
			.hasStatus(HttpStatus.FORBIDDEN)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("csrf_invalid");
		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "screen_event")).isZero();
	}

	@Test
	void withTheCsrfTokenAndNoLoginIsRecorded() {
		assertThat(mvc.post()
			.uri("/api/v1/usage-events")
			.contentType(MediaType.APPLICATION_JSON)
			.cookie(new Cookie("XSRF-TOKEN", CSRF))
			.header("X-XSRF-TOKEN", CSRF)
			.content(BODY)).hasStatus(HttpStatus.NO_CONTENT);
		flusher.flush();

		assertThat(jdbc.sql("SELECT login_status FROM usagelog.screen_event").query(String.class).single())
			.isEqualTo("signed_out");
	}

	@Test
	void searchRowsTellTheLoginStatusAndAnswersStayTheSame() throws Exception { // AC-25, AC-20 (on)
		long account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		Instant later = Instant.now().plus(15, ChronoUnit.MINUTES);
		Instant past = Instant.now().minus(1, ChronoUnit.DAYS);
		List<Cookie> cookies = new ArrayList<>();
		cookies.add(new Cookie("access_token", token(account, KEY, later)));
		cookies.add(null);
		cookies.add(new Cookie("access_token", token(account, randomKey(), later)));
		cookies.add(new Cookie("access_token", token(account, KEY, past)));

		List<String> bodies = new ArrayList<>();
		for (Cookie cookie : cookies) {
			var request = mvc.get().uri("/api/v1/books?title=채식주의자&page=1");
			if (cookie != null) {
				request.cookie(cookie);
			}
			var result = request.exchange();
			assertThat(result).hasStatusOk();
			bodies.add(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
		}
		flusher.flush();

		assertThat(jdbc.sql("SELECT login_status FROM usagelog.search_event ORDER BY id").query(String.class).list())
			.containsExactly("signed_in", "signed_out", "signed_out", "signed_in");
		assertThat(bodies).allSatisfy(body -> assertThat(body).isEqualTo(bodies.getFirst()));
	}

	private static String token(long account, byte[] key, Instant expiresAt) throws JOSEException {
		SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
			.subject(String.valueOf(account))
			.expirationTime(Date.from(expiresAt))
			.build());
		jwt.sign(new MACSigner(key));
		return jwt.serialize();
	}

	private static byte[] randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return key;
	}

}
