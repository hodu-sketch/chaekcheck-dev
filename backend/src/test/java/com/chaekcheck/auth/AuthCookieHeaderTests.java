package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Cookie attributes as the real server (Tomcat) writes them. MockMvc's response leaves out attributes such as
 * SameSite, so this runs on a random port.
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT,
		properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.naver.client-id=test-naver-id",
				"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
				"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
class AuthCookieHeaderTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@LocalServerPort
	int port;

	@Test
	void csrfCookieIsSecureLaxAndReadableOnEveryPath() throws IOException, InterruptedException {
		HttpResponse<Void> response;
		try (HttpClient client = HttpClient.newHttpClient()) {
			response = client.send(
					HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/csrf-token")).build(),
					HttpResponse.BodyHandlers.discarding());
		}

		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(response.headers().firstValue("Set-Cookie")).hasValueSatisfying(cookie -> assertThat(cookie)
			.startsWith("XSRF-TOKEN=")
			.contains("Path=/", "Secure", "SameSite=Lax")
			.doesNotContainIgnoringCase("HttpOnly")
			.doesNotContainIgnoringCase("Domain"));
	}

}
