package com.chaekcheck.aisearch.internal.vector;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link QuestionEncoder} on the embedding server ({@code embed-service/}, user 2026-10-25): {@code POST
 * <url>/api/v1/embed} with {@code {"text": question}} and the {@code X-Embed-Token} header, answered with
 * {@code {"vector": [...]}}. The question goes in the body only and is never logged; the server is reached inside the
 * VPC. A wrong status, a broken answer or the timeout throw, and the search goes on with keywords.
 */
final class RemoteQuestionEncoder implements QuestionEncoder {

	static final String PATH = "/api/v1/embed";

	static final String TOKEN_HEADER = "X-Embed-Token";

	private final HttpClient http;

	private final URI uri;

	private final String token;

	private final Duration timeout;

	private final JsonMapper json;

	RemoteQuestionEncoder(AiSearchProperties.Remote remote, JsonMapper json) {
		if (remote.url().isBlank()) {
			throw new IllegalStateException("no embedding server address (chaekcheck.aisearch.vector.remote.url)");
		}
		this.uri = URI.create(remote.url().replaceAll("/+$", "") + PATH);
		this.token = remote.token();
		this.timeout = remote.timeout();
		this.json = json;
		this.http = HttpClient.newBuilder().connectTimeout(remote.timeout()).build();
	}

	@Override
	public float[] encode(String question) {
		HttpRequest request = HttpRequest.newBuilder(uri)
			.timeout(timeout)
			.header("Content-Type", "application/json")
			.header(TOKEN_HEADER, token)
			.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of("text", question))))
			.build();
		HttpResponse<String> response;
		try {
			response = http.send(request, HttpResponse.BodyHandlers.ofString());
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("the embedding request was interrupted", e);
		}
		catch (java.io.IOException e) {
			throw new IllegalStateException("the embedding server did not answer: " + e.getClass().getSimpleName(), e);
		}
		if (response.statusCode() != 200) {
			throw new IllegalStateException("the embedding server answered " + response.statusCode());
		}
		JsonNode vector = json.readTree(response.body()).get("vector");
		if (vector == null || !vector.isArray() || vector.isEmpty()) {
			throw new IllegalStateException("the embedding server's answer has no vector");
		}
		float[] out = new float[vector.size()];
		for (int i = 0; i < out.length; i++) {
			out[i] = (float) vector.get(i).asDouble();
		}
		return out;
	}

}
