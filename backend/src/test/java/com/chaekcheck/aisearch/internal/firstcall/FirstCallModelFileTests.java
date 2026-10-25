package com.chaekcheck.aisearch.internal.firstcall;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The real {@code first-call-model.bin} against the outputs numpy computes from the same file
 * ({@code scripts/aisearch/eval/fixture_first_call.py} writes {@code first-call-fixture.json} beside it): 12 questions
 * as int8 KURE-v1 vectors. Runs only with {@code AISEARCH_FIRST_CALL_DIR} set (the model is not in the repository).
 */
@EnabledIfEnvironmentVariable(named = "AISEARCH_FIRST_CALL_DIR", matches = ".+")
class FirstCallModelFileTests {

	@Test
	void theJavaNetworkGivesWhatNumpyGives() throws Exception {
		Path dir = Path.of(System.getenv("AISEARCH_FIRST_CALL_DIR"));
		JsonMapper json = JsonMapper.builder().build();
		FirstCallModel model = FirstCallModel.read(dir.resolve(FirstCallModel.NAME), json);
		JsonNode rows = json.readTree(Files.readString(dir.resolve("first-call-fixture.json")));
		assertThat(rows.size()).isEqualTo(12);
		for (JsonNode row : rows) {
			float[] vector = new float[row.path("vector").size()];
			for (int i = 0; i < vector.length; i++) {
				vector[i] = (float) row.path("vector").get(i).asDouble();
			}
			double[] p = model.outputs("route", vector);
			for (int k = 0; k < p.length; k++) {
				assertThat(p[k]).isCloseTo(row.path("routeP").get(k).asDouble(), org.assertj.core.data.Offset.offset(1e-4));
			}
			assertThat(model.route(vector)).as(row.path("question").asString()).isEqualTo(row.path("route").asString());
			assertThat(model.labels("field", vector)).isEqualTo(strings(row.path("field")));
			assertThat(model.labels("subfield", vector)).isEqualTo(strings(row.path("subfield")));
		}
		assertThat(BookProfiles.read(dir.resolve(BookProfiles.NAME)).count()).isEqualTo(27_227);
	}

	private static List<String> strings(JsonNode node) {
		List<String> out = new ArrayList<>();
		node.forEach(n -> out.add(n.asString()));
		return out;
	}

}
