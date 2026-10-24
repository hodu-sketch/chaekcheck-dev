package com.chaekcheck.aisearch.internal.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * {@link LlmClient} for the Anthropic Messages API with the JDK {@code HttpClient} (cycle 24 handoff step 6, design
 * decision #5: no SDK). Structured output through {@code output_config.format} ({@code json_schema}); thinking is left
 * to the model's default (adaptive) and the depth is set with {@code output_config.effort} (step 1 measurement). The
 * stream's server-sent events are read line by line: the input tokens come with {@code message_start}, the output
 * tokens with the last {@code message_delta} (cumulative), text with {@code content_block_delta}/{@code text_delta}.
 * Logs carry the status, the error type and the request id only; never the key, the instructions or the
 * conversation.
 */
public final class AnthropicLlmClient implements LlmClient {

	private static final Logger log = LoggerFactory.getLogger(AnthropicLlmClient.class);

	/** At most this much of an error body is read, to find the error type. */
	private static final int ERROR_BODY_MAX = 8 * 1024;

	private final AnthropicProperties properties;

	private final HttpClient http;

	private final JsonMapper json;

	private final URI messages;

	public AnthropicLlmClient(AnthropicProperties properties, HttpClient http, JsonMapper json) {
		this.properties = properties;
		this.http = http;
		this.json = json;
		this.messages = properties.baseUrl().resolve("/v1/messages");
	}

	@Override
	public Result complete(LlmCall call, Duration timeout) {
		HttpRequest request = request(call, false, timeout);
		long started = System.nanoTime();
		HttpResponse<String> response = await(
				http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), timeout);
		if (response.statusCode() != 200) {
			throw refused(response.statusCode(), response.body(), requestId(response));
		}
		JsonNode body;
		try {
			body = json.readTree(response.body());
		}
		catch (JacksonException e) {
			throw failure(Failure.BAD_OUTPUT, Usage.NONE, "the answer is not JSON", requestId(response));
		}
		StringBuilder text = new StringBuilder();
		for (JsonNode block : body.path("content")) {
			if ("text".equals(block.path("type").asString(""))) {
				text.append(block.path("text").asString(""));
			}
		}
		JsonNode usage = body.path("usage");
		Usage counted = new Usage(usage.path("input_tokens").asInt(0), usage.path("output_tokens").asInt(0), true);
		String stopReason = body.path("stop_reason").asString("");
		log.debug("anthropic call: status 200, stop {}, tokens {}/{}, {} ms, request {}", stopReason,
				counted.inputTokens(), counted.outputTokens(), elapsedMs(started), requestId(response));
		if (!"end_turn".equals(stopReason)) {
			throw failure(Failure.BAD_OUTPUT, counted, "stop reason " + stopReason, requestId(response));
		}
		return new Result(text.toString(), counted, stopReason);
	}

	@Override
	public Result stream(LlmCall call, Duration timeout, Consumer<String> onText) {
		long deadline = System.nanoTime() + timeout.toNanos();
		HttpRequest request = request(call, true, timeout);
		HttpResponse<InputStream> response = await(http.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()),
				Duration.ofNanos(Math.max(1, deadline - System.nanoTime())));
		String requestId = requestId(response);
		InputStream in = response.body();
		AtomicBoolean finished = new AtomicBoolean();
		AtomicBoolean timedOut = new AtomicBoolean();
		// the whole call has one time: close the body when it runs out, which ends a blocking read (an error body
		// included); a consumer that blocks in onText is not interrupted, the next read ends instead
		Thread closer = Thread.ofVirtual().start(() -> {
			try {
				long left = deadline - System.nanoTime();
				if (left > 0) {
					TimeUnit.NANOSECONDS.sleep(left);
				}
				if (!finished.get()) {
					timedOut.set(true);
					closeQuietly(in);
				}
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		try {
			if (response.statusCode() != 200) {
				String errorBody = readSome(in);
				if (timedOut.get()) {
					throw failure(Failure.TIMEOUT, Usage.NONE, "error body over its time", requestId);
				}
				throw refused(response.statusCode(), errorBody, requestId);
			}
			return readEvents(in, onText, timedOut, requestId);
		}
		finally {
			finished.set(true);
			closer.interrupt();
			closeQuietly(in);
		}
	}

	/** The event loop of {@link #stream}: server-sent events, {@code data:} lines joined until a blank line. */
	private Result readEvents(InputStream in, Consumer<String> onText, AtomicBoolean timedOut, String requestId) {
		StreamState state = new StreamState();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
			StringBuilder data = new StringBuilder();
			String line;
			while (!state.stopped && (line = reader.readLine()) != null) {
				if (line.isEmpty()) {
					dispatch(data, state, onText, requestId);
				}
				else if (line.startsWith("data:")) {
					if (!data.isEmpty()) {
						data.append('\n');
					}
					data.append(line.substring(line.startsWith("data: ") ? 6 : 5));
				}
				// "event:", "id:", "retry:" and ":" comment lines: the JSON's own type is used instead
			}
			if (!state.stopped) {
				dispatch(data, state, onText, requestId);
			}
		}
		catch (IOException e) {
			if (timedOut.get()) {
				throw failure(Failure.TIMEOUT, state.usage(), "stream over its time", requestId);
			}
			throw failure(Failure.HTTP_ERROR, state.usage(), "stream broke: " + e.getClass().getSimpleName(), requestId);
		}
		if (!state.stopped) {
			throw failure(timedOut.get() ? Failure.TIMEOUT : Failure.HTTP_ERROR, state.usage(),
					"stream ended before message_stop", requestId);
		}
		log.debug("anthropic stream: stop {}, tokens {}/{}, request {}", state.stopReason, state.inputTokens,
				state.outputTokens, requestId);
		return new Result(state.text.toString(), state.usage(), state.stopReason);
	}

	private void dispatch(StringBuilder data, StreamState state, Consumer<String> onText, String requestId) {
		String payload = data.toString().strip();
		data.setLength(0);
		if (payload.isEmpty() || payload.equals("[DONE]")) {
			return;
		}
		JsonNode event;
		try {
			event = json.readTree(payload);
		}
		catch (JacksonException e) {
			// an event we cannot read: only the text and the usage matter, which are checked at the end
			log.debug("anthropic stream: an unreadable event skipped, request {}", requestId);
			return;
		}
		switch (event.path("type").asString("")) {
			case "message_start" -> state.inputTokens = event.path("message").path("usage").path("input_tokens").asInt(0);
			case "content_block_delta" -> {
				JsonNode delta = event.path("delta");
				if ("text_delta".equals(delta.path("type").asString(""))) {
					String piece = delta.path("text").asString("");
					state.text.append(piece);
					try {
						onText.accept(piece);
					}
					catch (RuntimeException e) {
						throw failure(Failure.CANCELLED, state.usage(),
								"the reader stopped: " + e.getClass().getSimpleName(), requestId);
					}
				}
			}
			case "message_delta" -> {
				JsonNode usage = event.path("usage");
				if (usage.has("output_tokens")) {
					state.outputTokens = usage.path("output_tokens").asInt(0);
					state.outputKnown = true;
				}
				String reason = event.path("delta").path("stop_reason").asString("");
				if (!reason.isEmpty()) {
					state.stopReason = reason;
				}
			}
			case "message_stop" -> state.stopped = true;
			case "error" -> {
				String type = event.path("error").path("type").asString("");
				throw failure("overloaded_error".equals(type) ? Failure.OVERLOADED : Failure.HTTP_ERROR, state.usage(),
						"stream error " + type, requestId);
			}
			default -> {
				// ping, content_block_start, content_block_stop: nothing to do
			}
		}
	}

	/** What the stream told so far. */
	private static final class StreamState {

		final StringBuilder text = new StringBuilder();

		int inputTokens;

		int outputTokens;

		boolean outputKnown;

		String stopReason = "";

		boolean stopped;

		Usage usage() {
			return new Usage(inputTokens, outputTokens, outputKnown);
		}

	}

	private HttpRequest request(LlmCall call, boolean stream, Duration timeout) {
		if (!properties.hasKey()) {
			throw new LlmException(Failure.NOT_CONFIGURED, Usage.NONE, "no API key");
		}
		ObjectNode body = json.createObjectNode();
		body.put("model", properties.model());
		body.put("max_tokens", call.maxTokens());
		body.put("system", call.system());
		ArrayNode turns = body.putArray("messages");
		for (Message message : call.messages()) {
			turns.addObject().put("role", message.role().wire()).put("content", message.text());
		}
		ObjectNode output = body.putObject("output_config");
		if (!properties.effort().isEmpty()) {
			output.put("effort", properties.effort());
		}
		ObjectNode format = output.putObject("format");
		format.put("type", "json_schema");
		try {
			format.set("schema", json.readTree(call.jsonSchema()));
		}
		catch (JacksonException e) {
			throw new IllegalArgumentException("the call's JSON schema is not JSON");
		}
		if (stream) {
			body.put("stream", true);
		}
		return HttpRequest.newBuilder(messages)
			.timeout(timeout)
			.header("content-type", "application/json")
			.header("accept", stream ? "text/event-stream" : "application/json")
			.header("x-api-key", properties.apiKey())
			.header("anthropic-version", properties.version())
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
			.build();
	}

	private <T> HttpResponse<T> await(CompletableFuture<HttpResponse<T>> sending, Duration timeout) {
		try {
			return sending.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException e) {
			sending.cancel(true);
			throw failure(Failure.TIMEOUT, Usage.NONE, "no answer in " + timeout.toMillis() + " ms", "");
		}
		catch (InterruptedException e) {
			sending.cancel(true);
			Thread.currentThread().interrupt();
			throw failure(Failure.TIMEOUT, Usage.NONE, "interrupted", "");
		}
		catch (ExecutionException e) {
			Failure kind = e.getCause() instanceof HttpTimeoutException ? Failure.TIMEOUT : Failure.HTTP_ERROR;
			throw failure(kind, Usage.NONE, "not sent: " + e.getCause().getClass().getSimpleName(), "");
		}
	}

	/** A refused request; only the error type is taken from the body. */
	private LlmException refused(int status, String body, String requestId) {
		String type = "";
		try {
			type = json.readTree(body == null ? "" : body).path("error").path("type").asString("");
		}
		catch (JacksonException e) {
			// not JSON: the status alone tells the kind
		}
		Failure kind = status == 429 || status == 529 || "overloaded_error".equals(type) ? Failure.OVERLOADED
				: Failure.HTTP_ERROR;
		return failure(kind, Usage.NONE, "status " + status + (type.isEmpty() ? "" : " " + type), requestId);
	}

	private static LlmException failure(Failure kind, Usage usage, String what, String requestId) {
		log.warn("anthropic call failed: {} ({}){}", kind, what, requestId.isEmpty() ? "" : ", request " + requestId);
		return new LlmException(kind, usage, what);
	}

	private static String requestId(HttpResponse<?> response) {
		return response.headers().firstValue("request-id").orElse("");
	}

	private static String readSome(InputStream in) {
		try (in) {
			return new String(in.readNBytes(ERROR_BODY_MAX), StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			return "";
		}
	}

	private static void closeQuietly(InputStream in) {
		try {
			in.close();
		}
		catch (IOException e) {
			// closing to stop a read; nothing else to do
		}
	}

	private static long elapsedMs(long startedNanos) {
		return (System.nanoTime() - startedNanos) / 1_000_000;
	}

}
