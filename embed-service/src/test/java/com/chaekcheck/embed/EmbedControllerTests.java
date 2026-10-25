package com.chaekcheck.embed;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/**
 * {@code /api/v1/embed} with a fake model: the vector for the right token, 401 for a wrong or missing one, 400 for no
 * or too long text; {@code /api/v1/health}.
 */
@SpringBootTest(properties = { "embed.token=test-token", "embed.max-chars=20" })
@AutoConfigureMockMvc
@Import(EmbedControllerTests.FakeModel.class)
class EmbedControllerTests {

	@TestConfiguration
	static class FakeModel {

		@Bean
		@Primary
		Embedder fakeEmbedder() {
			return new Embedder() {

				@Override
				public boolean ready() {
					return true;
				}

				@Override
				public float[] embed(String text) {
					return new float[] { 0.6f, 0.8f };
				}

			};
		}

	}

	@Autowired
	MockMvcTester mvc;

	@Test
	void theRightTokenGetsTheVector() {
		assertThat(post("test-token", "{\"text\":\"잠들기 전에 읽을 과학책\"}")).hasStatusOk()
			.bodyJson()
			.isEqualTo("{\"vector\":[0.6,0.8]}");
	}

	@Test
	void aWrongOrMissingTokenIs401() {
		assertThat(post("wrong", "{\"text\":\"과학책\"}")).hasStatus(401)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("unauthorized");
		assertThat(mvc.post().uri("/api/v1/embed").contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"책\"}"))
			.hasStatus(401);
	}

	@Test
	void noOrTooLongTextIs400() {
		assertThat(post("test-token", "{}")).hasStatus(400);
		assertThat(post("test-token", "{\"text\":\"  \"}")).hasStatus(400);
		assertThat(post("test-token", "{\"text\":\"" + "가".repeat(21) + "\"}")).hasStatus(400);
	}

	@Test
	void theHealthSaysReady() {
		assertThat(mvc.get().uri("/api/v1/health")).hasStatusOk().bodyJson().isEqualTo("{\"ready\":true}");
	}

	private org.springframework.test.web.servlet.assertj.MvcTestResult post(String token, String body) {
		return mvc.post()
			.uri("/api/v1/embed")
			.header(EmbedController.TOKEN_HEADER, token)
			.contentType(MediaType.APPLICATION_JSON)
			.content(body)
			.exchange();
	}

}
