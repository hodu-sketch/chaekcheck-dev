package com.chaekcheck.aisearch.internal.web;

import static com.chaekcheck.aisearch.internal.web.AiSearchControllerTests.assertProblem;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;

/** The feature off (the default): both addresses answer 404 {@code not_found} (cycle 24 step 0 #9). */
@SpringBootTest
@AutoConfigureMockMvc
class AiSearchOffTests {

	@Autowired
	MockMvcTester mvc;

	@Test
	void bothAddressesAreNotFound() {
		assertProblem(mvc.get().uri("/api/v1/ai-searches/status").exchange(), HttpStatus.NOT_FOUND, "not_found");
		assertProblem(mvc.post()
			.uri("/api/v1/ai-searches/stream")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"question\":\"과학책\",\"turnNo\":1}")
			.exchange(), HttpStatus.NOT_FOUND, "not_found");
	}

}
