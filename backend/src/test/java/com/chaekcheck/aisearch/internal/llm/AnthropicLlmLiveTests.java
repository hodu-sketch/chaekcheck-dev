package com.chaekcheck.aisearch.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmCall;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Message;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Result;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Role;

import tools.jackson.databind.json.JsonMapper;

/**
 * One real call of each kind against the Anthropic API (cycle 24 step 6), by hand only: it costs money and needs the
 * key, so it runs only with {@code CHAEKCHECK_AISEARCH_LIVE=1} (and the key in
 * {@code CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY}); a normal build skips it. Prints times and tokens, never the key.
 * <pre>
 * CHAEKCHECK_AISEARCH_LIVE=1 ./gradlew test --tests '*AnthropicLlmLiveTests'
 * </pre>
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_AISEARCH_LIVE", matches = "1")
class AnthropicLlmLiveTests {

	private static final String SCHEMA = """
			{"type":"object","properties":{"head":{"type":"string"},"picks":{"type":"array","items":{"type":"object",
			"properties":{"no":{"type":"integer"},"text":{"type":"string"}},"required":["no","text"],
			"additionalProperties":false}}},"required":["head","picks"],"additionalProperties":false}""";

	private final LlmClient client = new AnthropicLlmClient(
			new AnthropicProperties(System.getenv("CHAEKCHECK_AISEARCH_ANTHROPIC_API_KEY"), null, null, null, "low"),
			HttpClient.newHttpClient(), JsonMapper.builder().build());

	private final LlmCall call = new LlmCall("고를 수 있는 책 가운데 질문에 맞는 책을 0~2권 고르고 한 문장씩 쓴다. JSON으로만 답한다.",
			List.of(new Message(Role.USER, "질문: 짧은 과학책\n고를 수 있는 책:\n1. 제목: 코스모스 / 저자: 칼 세이건\n2. 제목: 토지 / 저자: 박경리")),
			SCHEMA, 2048);

	@Test
	void completeAndStreamAgainstTheRealApi() {
		long started = System.nanoTime();
		Result whole = client.complete(call, Duration.ofSeconds(15));
		System.out.printf("live complete: %d ms, tokens %d/%d, stop %s%n", (System.nanoTime() - started) / 1_000_000,
				whole.usage().inputTokens(), whole.usage().outputTokens(), whole.stopReason());
		assertThat(whole.text()).startsWith("{").contains("\"picks\"");

		List<String> pieces = new ArrayList<>();
		started = System.nanoTime();
		Result streamed = client.stream(call, Duration.ofSeconds(15), pieces::add);
		System.out.printf("live stream: %d ms, %d pieces, tokens %d/%d, stop %s%n",
				(System.nanoTime() - started) / 1_000_000, pieces.size(), streamed.usage().inputTokens(),
				streamed.usage().outputTokens(), streamed.stopReason());
		assertThat(String.join("", pieces)).isEqualTo(streamed.text());
		assertThat(streamed.usage().outputKnown()).isTrue();
		assertThat(streamed.text()).startsWith("{\"head\"");
	}

}
