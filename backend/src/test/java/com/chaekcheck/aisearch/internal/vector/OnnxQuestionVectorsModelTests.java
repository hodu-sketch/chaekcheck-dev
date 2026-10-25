package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link OnnxQuestionVectors} with the real model folder (only when {@code AISEARCH_MODEL_DIR} names it; the files are
 * about 420 MB and not in the repository): the question vectors match the ones Python's onnxruntime and tokenizers made
 * with the same files ({@code parity.json}), and the nearest books come back.
 */
@EnabledIfEnvironmentVariable(named = "AISEARCH_MODEL_DIR", matches = ".+")
class OnnxQuestionVectorsModelTests {

	@Test
	void theQuestionVectorsAreThePythonOnes() throws Exception {
		Path dir = Path.of(System.getenv("AISEARCH_MODEL_DIR"));
		try (OnnxQuestionVectors vectors = new OnnxQuestionVectors(properties(dir))) {
			assertThat(vectors.on()).isTrue();
			JsonNode parity = JsonMapper.builder().build().readTree(Files.readString(dir.resolve("parity.json")));
			for (JsonNode item : parity) {
				float[] mine = vectors.encode(item.get("text").asString());
				double dot = 0;
				for (int i = 0; i < mine.length; i++) {
					dot += mine[i] * item.get("vector").get(i).asDouble();
				}
				assertThat(dot).as(item.get("text").asString()).isGreaterThan(0.999);
			}
			List<Long> near = vectors.nearest("잠들기 전에 읽을 과학책", 5).get();
			assertThat(near).hasSize(5).doesNotHaveDuplicates();
		}
	}

	@Test
	void aMissingFolderLeavesTheVectorsOff() {
		try (OnnxQuestionVectors vectors = new OnnxQuestionVectors(properties(Path.of("no-such-folder")))) {
			assertThat(vectors.on()).isFalse();
		}
	}

	private static AiSearchProperties properties(Path dir) {
		return new AiSearchProperties(true, java.time.ZoneId.of("Asia/Seoul"), Duration.ofSeconds(5),
				Duration.ofSeconds(15), 512, 3072, 20, new AiSearchProperties.Rate(10, 100),
				new AiSearchProperties.Budget(java.math.BigDecimal.TWO),
				new AiSearchProperties.Price(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
				new AiSearchProperties.Limits(30, 8, 5, 200, 300),
				new AiSearchProperties.Vector(true, dir.toString(), 2, 50, 60, 60, Duration.ofSeconds(2)));
	}

}
