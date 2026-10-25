package com.chaekcheck.embed;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link QueryEncoder} with the real model folder (only when {@code AISEARCH_MODEL_DIR} names it; the files are about
 * 420 MB and not in the repository): the vectors match the ones Python's onnxruntime and tokenizers made with the same
 * files ({@code parity.json}), as the main service's {@code KureQuestionVectorsModelTests} checks.
 */
@EnabledIfEnvironmentVariable(named = "AISEARCH_MODEL_DIR", matches = ".+")
class QueryEncoderModelTests {

	@Test
	void theVectorsAreThePythonOnes() throws Exception {
		Path dir = Path.of(System.getenv("AISEARCH_MODEL_DIR"));
		try (QueryEncoder encoder = new QueryEncoder(dir, 2)) {
			JsonNode parity = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("parity.json")));
			for (JsonNode item : parity) {
				float[] mine = encoder.encode(item.get("text").asString());
				double dot = 0;
				for (int i = 0; i < mine.length; i++) {
					dot += mine[i] * item.get("vector").get(i).asDouble();
				}
				assertThat(dot).as(item.get("text").asString()).isGreaterThan(0.999);
			}
		}
	}

}
