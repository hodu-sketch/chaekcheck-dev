package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * AC-22: with only Kakao in {@code chaekcheck.auth.providers}, the server starts without any Naver key, every Naver
 * login address (start, callback, re-authentication) is 404, and Kakao login works as before.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.providers=kakao",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret",
		"chaekcheck.auth.naver.client-id=", "chaekcheck.auth.naver.client-secret=" })
@AutoConfigureMockMvc
@Transactional
class ProviderToggleTests {

	static final FakeProvider fake = FakeProvider.start();

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
		registry.add("chaekcheck.auth.kakao.authorization-uri", () -> fake.url("/kakao/authorize"));
		registry.add("chaekcheck.auth.kakao.token-uri", () -> fake.url("/kakao/token"));
		registry.add("chaekcheck.auth.kakao.user-info-uri", () -> fake.url("/kakao/me"));
	}

	@AfterAll
	static void stopFake() {
		fake.server.stop(0);
	}

	@Autowired
	MockMvcTester mvc;

	@Test
	void naverAddressesAreNotFound() {
		for (String path : List.of("/api/v1/auth/oauth2/authorization/naver",
				"/api/v1/auth/oauth2/authorization/naver-reauth",
				"/api/v1/auth/login/oauth2/code/naver?code=c&state=s",
				"/api/v1/auth/login/oauth2/code/naver-reauth?code=c&state=s")) {
			assertThat(mvc.get().uri(path)).as(path)
				.hasStatus(HttpStatus.NOT_FOUND)
				.bodyJson()
				.extractingPath("$.code")
				.isEqualTo("not_found");
		}
	}

	@Test
	void theWebSeesOnlyKakao() {
		assertThat(mvc.get().uri("/api/v1/auth/providers")).hasStatusOk().bodyJson().isStrictlyEqualTo("[\"KAKAO\"]");
	}

	@Test
	void kakaoLoginStillWorks() {
		fake.kakaoUser = "{\"id\":31337}";
		MockHttpSession session = new MockHttpSession();
		MvcTestResult start = mvc.get().uri("/api/v1/auth/oauth2/authorization/kakao").session(session).exchange();
		assertThat(start).hasStatus(HttpStatus.FOUND);
		String state = URLDecoder.decode(UriComponentsBuilder.fromUriString(start.getResponse().getRedirectedUrl())
			.build()
			.getQueryParams()
			.getFirst("state"), StandardCharsets.UTF_8);

		assertThat(mvc.get()
			.uri("/api/v1/auth/login/oauth2/code/kakao")
			.param("code", "test-code")
			.param("state", state)
			.session(session)).hasHeader(HttpHeaders.LOCATION, "/signup");
		assertThat(mvc.get().uri("/api/v1/auth/oauth2/authorization/kakao-reauth")).hasStatus(HttpStatus.FOUND);
	}

}
