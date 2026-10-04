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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * Cycle 06 step 1 (C10): a controller of another module gets the signed-in account as an {@link AccountId}
 * parameter. The test-only controller is registered for this class only.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
@Transactional
@Import(AccountIdArgumentResolverTests.ProbeConfiguration.class)
class AccountIdArgumentResolverTests {

	static final byte[] KEY = randomBytes(32);

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(KEY));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void fillsTheSignedInAccount() {
		long account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();

		assertThat(mvc.get().uri("/api/v1/me/probe").cookie(new Cookie("access_token", token(account))))
			.hasStatusOk()
			.bodyText()
			.isEqualTo(String.valueOf(account));
	}

	@Test
	void withoutTokenIsUnauthorized() {
		assertUnauthorized(mvc.get().uri("/api/v1/me/probe").exchange());
	}

	@Test
	void outsideTheLoginPathsIsUnauthorized() {
		long account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();

		// The cookie is read only under /api/v1/me/** (C9), so here nobody is signed in
		assertUnauthorized(mvc.get().uri("/api/v1/probe").cookie(new Cookie("access_token", token(account))).exchange());
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status":401,"detail":"Login required","code":"unauthorized"}
					""");
	}

	private static String token(long account) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256),
					new JWTClaimsSet.Builder().subject(String.valueOf(account))
						.expirationTime(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
						.build());
			jwt.sign(new MACSigner(KEY));
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

	@TestConfiguration(proxyBeanMethods = false)
	static class ProbeConfiguration {

		@Bean
		ProbeController probeController() {
			return new ProbeController();
		}

	}

	@RestController
	static class ProbeController {

		@GetMapping({ "/api/v1/me/probe", "/api/v1/probe" })
		String probe(AccountId account) {
			return String.valueOf(account.value());
		}

	}

}
