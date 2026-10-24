package com.chaekcheck.aisearch.internal.llm;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.aisearch.internal.llm.LlmClient.Failure;
import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmCall;
import com.chaekcheck.aisearch.internal.llm.LlmClient.LlmException;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Message;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Result;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Role;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Usage;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link AnthropicLlmClient} against a fake Messages API (JDK {@code HttpServer}, cycle 24 handoff step 6): request
 * headers and body, a whole answer, a streamed answer with its usage, 429 / 529 / 5xx / overloaded events, the time
 * limit, a stream that breaks off, an empty key, and no key or question in the logs.
 */
@ExtendWith(OutputCaptureExtension.class)
class AnthropicLlmClientTests {

	private static final String KEY = "sk-test-key-never-logged";

	private static final String QUESTION = "잠들기 전에 조금씩 읽을 과학책";

	private static final String SCHEMA = """
			{"type":"object","properties":{"head":{"type":"string"}},"required":["head"],"additionalProperties":false}""";

	private static final LlmCall CALL = new LlmCall("지시문", List.of(new Message(Role.USER, QUESTION)), SCHEMA, 512);

	private final JsonMapper json = JsonMapper.builder().build();

	private HttpServer server;

	private final AtomicReference<Exchange> seen = new AtomicReference<>();

	private volatile Handler handler;

	record Exchange(String method, String path, String key, String version, String contentType, JsonNode body) {
	}

	interface Handler {

		void handle(HttpExchange exchange) throws IOException, InterruptedException;

	}

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", exchange -> {
			try {
				byte[] body = exchange.getRequestBody().readAllBytes();
				seen.set(new Exchange(exchange.getRequestMethod(), exchange.getRequestURI().getPath(),
						exchange.getRequestHeaders().getFirst("x-api-key"),
						exchange.getRequestHeaders().getFirst("anthropic-version"),
						exchange.getRequestHeaders().getFirst("content-type"),
						json.readTree(new String(body, StandardCharsets.UTF_8))));
				handler.handle(exchange);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			catch (IOException e) {
				// the client went away (time limit tests)
			}
			finally {
				exchange.close();
			}
		});
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.start();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void sendsTheMessagesRequestAndReadsAWholeAnswer() {
		handler = exchange -> reply(exchange, 200, "application/json", """
				{"type":"message","content":[{"type":"thinking","thinking":""},{"type":"text","text":"{\\"head\\":\\"좋아요\\"}"}],
				 "stop_reason":"end_turn","usage":{"input_tokens":910,"output_tokens":65}}""");

		Result result = client(KEY).complete(CALL, Duration.ofSeconds(5));

		assertThat(result.text()).isEqualTo("{\"head\":\"좋아요\"}");
		assertThat(result.usage()).isEqualTo(new Usage(910, 65, true));
		Exchange request = seen.get();
		assertThat(request.method()).isEqualTo("POST");
		assertThat(request.path()).isEqualTo("/v1/messages");
		assertThat(request.key()).isEqualTo(KEY);
		assertThat(request.version()).isEqualTo("2026-10-21");
		assertThat(request.contentType()).isEqualTo("application/json");
		JsonNode body = request.body();
		assertThat(body.path("model").asString()).isEqualTo("claude-haiku-5-5");
		assertThat(body.path("max_tokens").asInt()).isEqualTo(512);
		assertThat(body.path("system").asString()).isEqualTo("지시문");
		assertThat(body.path("messages").get(0).path("role").asString()).isEqualTo("user");
		assertThat(body.path("messages").get(0).path("content").asString()).isEqualTo(QUESTION);
		assertThat(body.path("output_config").path("effort").asString()).isEqualTo("low");
		assertThat(body.path("output_config").path("format").path("type").asString()).isEqualTo("json_schema");
		assertThat(body.path("output_config").path("format").path("schema").path("required").get(0).asString())
			.isEqualTo("head");
		assertThat(body.has("thinking")).isFalse();
		assertThat(body.has("stream")).isFalse();
		assertThat(body.has("temperature")).isFalse();
	}

	@Test
	void aWholeAnswerCutAtMaxTokensIsBadOutput() {
		handler = exchange -> reply(exchange, 200, "application/json", """
				{"content":[{"type":"text","text":"{\\"head\\":\\"좋"}],"stop_reason":"max_tokens",
				 "usage":{"input_tokens":900,"output_tokens":512}}""");

		assertThatThrownBy(() -> client(KEY).complete(CALL, Duration.ofSeconds(5)))
			.isInstanceOfSatisfying(LlmException.class, e -> {
				assertThat(e.failure()).isEqualTo(Failure.BAD_OUTPUT);
				assertThat(e.usage()).isEqualTo(new Usage(900, 512, true));
			});
	}

	@Test
	void streamsTheTextInOrderAndReadsTheUsageFromStartAndDelta() {
		handler = exchange -> {
			start(exchange);
			event(exchange, "message_start", """
					{"type":"message_start","message":{"usage":{"input_tokens":1800,"output_tokens":3}}}""");
			event(exchange, "content_block_start", """
					{"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}""");
			event(exchange, "ping", "{\"type\":\"ping\"}");
			event(exchange, "content_block_delta", """
					{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"{\\"head\\":\\"잠"}}""");
			event(exchange, "content_block_delta", """
					{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"들기\\"}"}}""");
			event(exchange, "message_delta", """
					{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":420}}""");
			event(exchange, "message_stop", "{\"type\":\"message_stop\"}");
		};
		List<String> pieces = new ArrayList<>();

		Result result = client(KEY).stream(CALL, Duration.ofSeconds(5), pieces::add);

		assertThat(pieces).containsExactly("{\"head\":\"잠", "들기\"}");
		assertThat(result.text()).isEqualTo("{\"head\":\"잠들기\"}");
		assertThat(result.usage()).isEqualTo(new Usage(1800, 420, true));
		assertThat(result.stopReason()).isEqualTo("end_turn");
		assertThat(seen.get().body().path("stream").asBoolean()).isTrue();
	}

	@Test
	void busyAndRefusedAnswersAreTold() {
		handler = exchange -> reply(exchange, 429, "application/json",
				"{\"type\":\"error\",\"error\":{\"type\":\"rate_limit_error\",\"message\":\"slow down\"}}");
		assertFailure(() -> client(KEY).complete(CALL, Duration.ofSeconds(5)), Failure.OVERLOADED);

		handler = exchange -> reply(exchange, 529, "application/json",
				"{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}");
		assertFailure(() -> client(KEY).stream(CALL, Duration.ofSeconds(5), piece -> {
		}), Failure.OVERLOADED);

		handler = exchange -> reply(exchange, 500, "text/plain", "oops");
		assertFailure(() -> client(KEY).complete(CALL, Duration.ofSeconds(5)), Failure.HTTP_ERROR);

		handler = exchange -> {
			start(exchange);
			event(exchange, "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":7}}}");
			event(exchange, "error", "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"x\"}}");
		};
		assertThatThrownBy(() -> client(KEY).stream(CALL, Duration.ofSeconds(5), piece -> {
		})).isInstanceOfSatisfying(LlmException.class, e -> {
			assertThat(e.failure()).isEqualTo(Failure.OVERLOADED);
			assertThat(e.usage()).isEqualTo(new Usage(7, 0, false));
		});
	}

	@Test
	void theTimeCoversTheWholeCall() {
		handler = exchange -> {
			Thread.sleep(3_000);
			reply(exchange, 200, "application/json", "{}");
		};
		long started = System.nanoTime();
		assertFailure(() -> client(KEY).complete(CALL, Duration.ofMillis(300)), Failure.TIMEOUT);

		// the stream starts in time but does not end in time
		handler = exchange -> {
			start(exchange);
			event(exchange, "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}");
			event(exchange, "content_block_delta",
					"{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{\"}}");
			Thread.sleep(3_000);
		};
		List<String> pieces = new ArrayList<>();
		assertThatThrownBy(() -> client(KEY).stream(CALL, Duration.ofMillis(500), pieces::add))
			.isInstanceOfSatisfying(LlmException.class, e -> {
				assertThat(e.failure()).isEqualTo(Failure.TIMEOUT);
				assertThat(e.usage()).isEqualTo(new Usage(5, 0, false));
			});
		assertThat(pieces).containsExactly("{");
		assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(2_500));
	}

	@Test
	void aStreamThatBreaksOffIsAnHttpErrorWithTheUsageSoFar() {
		handler = exchange -> {
			start(exchange);
			event(exchange, "message_start", "{\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":9}}}");
			event(exchange, "content_block_delta",
					"{\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"{\\\"he\"}}");
		};

		assertThatThrownBy(() -> client(KEY).stream(CALL, Duration.ofSeconds(5), piece -> {
		})).isInstanceOfSatisfying(LlmException.class, e -> {
			assertThat(e.failure()).isEqualTo(Failure.HTTP_ERROR);
			assertThat(e.usage()).isEqualTo(new Usage(9, 0, false));
		});
	}

	@Test
	void anEmptyKeySendsNothing() {
		handler = exchange -> reply(exchange, 200, "application/json", "{}");

		assertFailure(() -> client("  ").complete(CALL, Duration.ofSeconds(5)), Failure.NOT_CONFIGURED);
		assertFailure(() -> client("").stream(CALL, Duration.ofSeconds(5), piece -> {
		}), Failure.NOT_CONFIGURED);
		assertThat(seen.get()).isNull();
	}

	@Test
	void neitherTheKeyNorTheQuestionIsLoggedOrThrown(CapturedOutput output) {
		handler = exchange -> reply(exchange, 400, "application/json",
				"{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"" + QUESTION + "\"}}");

		assertThatThrownBy(() -> client(KEY).complete(CALL, Duration.ofSeconds(5)))
			.satisfies(e -> assertThat(e.getMessage()).doesNotContain(KEY, QUESTION));
		assertThat(new AnthropicProperties(KEY, null, null, null, null).toString()).doesNotContain(KEY);
		assertThat(output.getAll()).contains("anthropic call failed").doesNotContain(KEY, QUESTION, "지시문");
	}

	private AnthropicLlmClient client(String key) {
		AnthropicProperties properties = new AnthropicProperties(key, null,
				URI.create("http://127.0.0.1:" + server.getAddress().getPort()), null, "low");
		return new AnthropicLlmClient(properties, HttpClient.newHttpClient(), json);
	}

	private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call, Failure failure) {
		assertThatThrownBy(call).isInstanceOfSatisfying(LlmException.class, e -> assertThat(e.failure()).isEqualTo(failure));
	}

	private static void reply(HttpExchange exchange, int status, String type, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("content-type", type);
		exchange.getResponseHeaders().add("request-id", "req_test");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
	}

	private static void start(HttpExchange exchange) throws IOException {
		exchange.getResponseHeaders().add("content-type", "text/event-stream");
		exchange.sendResponseHeaders(200, 0);
	}

	private static void event(HttpExchange exchange, String name, String data) throws IOException {
		OutputStream out = exchange.getResponseBody();
		out.write(("event: " + name + "\ndata: " + data.strip() + "\n\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

}
