package com.chaekcheck.aisearch.internal.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The AI search settings (cycle 24, design-delta "설정값", plan settings-index 10절; values in {@code application.yml},
 * listed in docs/maintenance/settings-index.md 1-1절). Every value comes from the file; nothing has a default here.
 * @param enabled off by default; when off the address answers 404 and the web hides the AI tab
 * @param zone the day of the daily limits (Asia/Seoul)
 * @param keywordTimeout the first call (5 s [확정])
 * @param pickTimeout the whole second call (15 s [확정])
 * @param keywordMaxTokens the first call's cap (step 1: 512 [안])
 * @param pickMaxTokens the second call's cap (step 1: 2048 [안]; 3072 since v1.12.1, user 2026-10-25)
 * @param maxConcurrent AI searches running at once on this server (20, user 2026-10-24)
 * @param vector the question vector candidates mixed with the keyword ones (user 2026-10-25)
 */
@ConfigurationProperties("chaekcheck.aisearch")
public record AiSearchProperties(boolean enabled, ZoneId zone, Duration keywordTimeout, Duration pickTimeout,
		int keywordMaxTokens, int pickMaxTokens, int maxConcurrent, Rate rate, Budget budget, Price price,
		Limits limits, Vector vector) {

	public AiSearchProperties {
		Objects.requireNonNull(zone, "chaekcheck.aisearch.zone");
		Objects.requireNonNull(keywordTimeout, "chaekcheck.aisearch.keyword-timeout");
		Objects.requireNonNull(pickTimeout, "chaekcheck.aisearch.pick-timeout");
		Objects.requireNonNull(rate, "chaekcheck.aisearch.rate");
		Objects.requireNonNull(budget, "chaekcheck.aisearch.budget");
		Objects.requireNonNull(price, "chaekcheck.aisearch.price");
		Objects.requireNonNull(limits, "chaekcheck.aisearch.limits");
		Objects.requireNonNull(vector, "chaekcheck.aisearch.vector");
	}

	/** Searches from one IP (10 a minute, 100 a day [확정], follow-up questions too). */
	public record Rate(int perMinute, int perDay) {
	}

	/** The day's model cost cap, checked when a search starts (US$2 [확정]). */
	public record Budget(BigDecimal dailyUsd) {
	}

	/** US$ per million tokens for prompts of 100k tokens or fewer (0.10 / 0.50 [확정], 2026-10-24). */
	public record Price(BigDecimal inputPerMtok, BigDecimal outputPerMtok) {
	}

	/**
	 * Sizes (all [확정] by design decision #8 and the spec): books given to the second call, books in one answer,
	 * earlier turns taken, question length, card text length.
	 */
	public record Limits(int candidates, int picks, int historyTurns, int questionMaxLength, int cardTextMaxChars) {
	}

	/**
	 * Question vectors (user 2026-10-25 "운영 코드로 해줘", measured in docs/reports/ai-search-eval-2026-10-25.md):
	 * the int8 KURE-v1 query model turns the question into a vector while the first call runs, the nearest book
	 * vectors the plan libraries hold are mixed with the keyword candidates by reciprocal rank fusion, and the first
	 * {@code Limits.candidates} of the mix go to the second call. Off, or with a model file missing, the keyword
	 * candidates alone are used as before.
	 * @param enabled off by default
	 * @param modelDir the folder with {@code book-vectors.bin}, and for {@link Encoder#LOCAL} also
	 * {@code kure-query-int8-ext.onnx} (+ {@code .data}) and {@code tokenizer-cut.json}
	 * @param threads threads of one question's model run (2: 106 ms on the server, 2026-10-25)
	 * @param keywordDepth keyword candidates taken into the mix (50 [안], the report's H)
	 * @param vectorDepth vector candidates taken into the mix (60 [안])
	 * @param rrfK the fusion constant (60 [안], the usual value)
	 * @param maxWait how long the search waits for the question vector after the first call; later, keywords alone
	 * @param encoder where the question becomes a vector (user 2026-10-25 "서로 다른 곳에서 소통하나 서버에 직접
	 * 임베딩하나 이걸 선택으로서 가능하게"): {@link Encoder#LOCAL} in this process, {@link Encoder#REMOTE} on the
	 * embedding server ({@code embed-service/}); the book vectors stay in this process either way
	 * @param remote the embedding server, for {@link Encoder#REMOTE}
	 */
	public record Vector(boolean enabled, String modelDir, int threads, int keywordDepth, int vectorDepth, int rrfK,
			Duration maxWait, Encoder encoder, Remote remote) {

		public Vector {
			Objects.requireNonNull(maxWait, "chaekcheck.aisearch.vector.max-wait");
			Objects.requireNonNull(encoder, "chaekcheck.aisearch.vector.encoder");
			Objects.requireNonNull(remote, "chaekcheck.aisearch.vector.remote");
			modelDir = modelDir == null ? "" : modelDir;
		}

	}

	/** Where the question becomes a vector. */
	public enum Encoder {
		/** The int8 KURE-v1 model in this process (about 450 MB on the server). */
		LOCAL,
		/** The embedding server over HTTP; this process holds the book vectors only. */
		REMOTE
	}

	/**
	 * The embedding server ({@code embed-service/}, user 2026-10-25 "micro 한 대를 임베딩 전용으로 두자").
	 * @param url its address, e.g. {@code http://10.0.1.23:8090}; reached inside the VPC only
	 * @param token sent as {@code X-Embed-Token}; a secret (Parameter Store), never logged
	 * @param timeout the whole request; later, the search goes on with keywords alone
	 */
	public record Remote(String url, String token, Duration timeout) {

		public Remote {
			Objects.requireNonNull(timeout, "chaekcheck.aisearch.vector.remote.timeout");
			url = url == null ? "" : url;
			token = token == null ? "" : token;
		}

	}

}
