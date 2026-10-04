package com.chaekcheck.mylibrary;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

/**
 * Login off (C7): the "내 도서관" addresses do not exist, while the public library list still works. Same settings as
 * {@code AuthDisabledTests}, so both share one context.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=false", "AUTH_JWT_SECRET=", "NAVER_CLIENT_ID=",
		"NAVER_CLIENT_SECRET=", "KAKAO_REST_API_KEY=", "KAKAO_CLIENT_SECRET=" })
@AutoConfigureMockMvc
class MyLibraryDisabledTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void myLibraryAddressesAreNotFound() {
		assertNotFound(mvc.get().uri("/api/v1/me/regions").exchange());
		assertNotFound(mvc.put()
			.uri("/api/v1/me/regions")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"residenceRegionIds\":[],\"workRegionIds\":[]}")
			.exchange());
		assertNotFound(mvc.get().uri("/api/v1/me/libraries").exchange());
		assertNotFound(mvc.put().uri("/api/v1/me/libraries/2").exchange());
		assertNotFound(mvc.delete().uri("/api/v1/me/libraries/2").exchange());
	}

	@Test
	void publicLibraryListWorks() {
		assertThat(mvc.get().uri("/api/v1/libraries")).hasStatusOk();
	}

	private static void assertNotFound(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.NOT_FOUND).bodyJson().extractingPath("$.code").isEqualTo("not_found");
	}

}
