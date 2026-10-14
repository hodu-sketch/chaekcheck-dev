package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * Login off (the default) behaves as cycle 04 (AC-21, server part): starts without any key, has no security
 * filter or security headers, and the login addresses do not exist.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=false", "AUTH_JWT_SECRET=", "NAVER_CLIENT_ID=",
		"NAVER_CLIENT_SECRET=", "KAKAO_REST_API_KEY=", "KAKAO_CLIENT_SECRET=" })
@AutoConfigureMockMvc
class AuthDisabledTests {

	@Autowired
	MockMvcTester mvc;

	@Autowired
	ApplicationContext context;

	@Test
	void hasNoSecurityFilterChain() {
		assertThat(context.getBeanNamesForType(SecurityFilterChain.class)).isEmpty();
		assertThat(context.containsBean("springSecurityFilterChain")).isFalse();
	}

	@Test
	void loginAddressesAreNotFound() {
		assertThat(mvc.get().uri("/api/v1/me")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("not_found");
		assertThat(mvc.get().uri("/api/v1/auth/providers")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("not_found");
		assertThat(mvc.get().uri("/api/v1/auth/csrf-token")).hasStatus(HttpStatus.NOT_FOUND)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("not_found");
	}

	/** Cycle 16 (ADR-0100): the terms stay readable through the API when login is off; only the web route hides. */
	@Test
	void noticesAndDocumentsAreOpen() {
		for (String uri : new String[] { "/api/v1/notices", "/api/v1/documents/terms-of-service",
				"/api/v1/documents/privacy-policy" }) {
			assertThat(mvc.get().uri(uri)).as(uri).hasStatusOk();
		}
	}

	@Test
	void existingApiHasNoSecurityHeaders() {
		assertThat(mvc.get().uri("/api/v1/regions")).hasStatusOk()
			.doesNotContainHeader("X-Frame-Options")
			.doesNotContainHeader("X-Content-Type-Options")
			.doesNotContainHeader("Cache-Control")
			.doesNotContainHeader("Set-Cookie");
	}

}
