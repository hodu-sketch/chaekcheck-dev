package com.chaekcheck.aisearch.internal.quota;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Usage;

/**
 * The day's model cost and counts in {@code aisearch.daily_usage}, one row per day of {@code chaekcheck.aisearch.zone}
 * (cycle 24, ADR-0152): kept in the database so a restart does not reset the cost. The cap is checked only when a
 * search starts, so searches already running may pass it (design review, accepted). Cost in millionths of a US
 * dollar, rounded up: plain input × input price, cache reads × input price × 0.1, cache writes × input price × 1.25,
 * output × output price, prices per million tokens.
 */
@Component
public class DailyBudget {

	private static final BigDecimal CACHE_READ_FACTOR = new BigDecimal("0.1");

	private static final BigDecimal CACHE_CREATION_FACTOR = new BigDecimal("1.25");

	/** The counted columns; only these names go into the statement. */
	enum Count {

		REQUEST("request_count");

		final String column;

		Count(String column) {
			this.column = column;
		}

	}

	private final JdbcClient jdbc;

	private final AtomicInteger rateLimited = new AtomicInteger();

	private final AtomicInteger paused = new AtomicInteger();

	private final long dailyMicroUsd;

	private final BigDecimal inputPrice;

	private final BigDecimal outputPrice;

	private final ZoneId zone;

	private final Clock clock;

	@Autowired
	DailyBudget(JdbcClient jdbc, AiSearchProperties properties) {
		this(jdbc, properties, Clock.systemUTC());
	}

	DailyBudget(JdbcClient jdbc, AiSearchProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.dailyMicroUsd = properties.budget().dailyUsd().movePointRight(6).setScale(0, RoundingMode.DOWN).longValueExact();
		this.inputPrice = properties.price().inputPerMtok();
		this.outputPrice = properties.price().outputPerMtok();
		this.zone = properties.zone();
		this.clock = clock;
	}

	/** Whether today's cost is still under the cap. */
	public boolean allowsStart() {
		Long spent = jdbc.sql("SELECT cost_micro_usd FROM aisearch.daily_usage WHERE day = :day")
			.param("day", today())
			.query(Long.class)
			.optional()
			.orElse(0L);
		return spent < dailyMicroUsd;
	}

	/** One search started. */
	public void countRequest() {
		add(Count.REQUEST);
	}

	/**
	 * One search refused by the IP limits (HTTP 429): counted in memory and written by {@link #flushRefusals} (step 8
	 * security review: a flood of refusals must not write a row each).
	 */
	public void countRateLimited() {
		rateLimited.incrementAndGet();
	}

	/** One search refused by the cost cap (HTTP 503), counted like {@link #countRateLimited}. */
	public void countPaused() {
		paused.incrementAndGet();
	}

	/** Writes the refusals counted since the last time, every 10 seconds and when the server stops. */
	@Scheduled(fixedDelay = 10, timeUnit = TimeUnit.SECONDS)
	@PreDestroy
	public void flushRefusals() {
		int limited = rateLimited.getAndSet(0);
		int rested = paused.getAndSet(0);
		if (limited == 0 && rested == 0) {
			return;
		}
		jdbc.sql("""
				INSERT INTO aisearch.daily_usage AS d (day, rate_limited_count, paused_count) VALUES (:day, :limited, :paused)
				ON CONFLICT (day) DO UPDATE SET rate_limited_count = d.rate_limited_count + EXCLUDED.rate_limited_count,
				  paused_count = d.paused_count + EXCLUDED.paused_count""")
			.param("day", today())
			.param("limited", limited)
			.param("paused", rested)
			.update();
	}

	/**
	 * One model call. When the call broke before the model told its output, the output is counted as
	 * {@code maxTokens} (handoff step 7-2 [안]).
	 * @return the output tokens counted
	 */
	public long addCall(Usage usage, int maxTokens) {
		long output = usage.outputKnown() ? usage.outputTokens() : Math.max(usage.outputTokens(), maxTokens);
		jdbc.sql("""
				INSERT INTO aisearch.daily_usage AS d (day, llm_call_count, input_tokens, output_tokens,
				  cache_read_tokens, cache_creation_tokens, cost_micro_usd)
				VALUES (:day, 1, :input, :output, :cacheRead, :cacheCreation, :cost)
				ON CONFLICT (day) DO UPDATE SET llm_call_count = d.llm_call_count + 1,
				  input_tokens = d.input_tokens + EXCLUDED.input_tokens,
				  output_tokens = d.output_tokens + EXCLUDED.output_tokens,
				  cache_read_tokens = d.cache_read_tokens + EXCLUDED.cache_read_tokens,
				  cache_creation_tokens = d.cache_creation_tokens + EXCLUDED.cache_creation_tokens,
				  cost_micro_usd = d.cost_micro_usd + EXCLUDED.cost_micro_usd""")
			.param("day", today())
			.param("input", (long) usage.totalInputTokens())
			.param("output", output)
			.param("cacheRead", (long) usage.cacheReadTokens())
			.param("cacheCreation", (long) usage.cacheCreationTokens())
			.param("cost", costMicroUsd(usage, output))
			.update();
		return output;
	}

	/** Millionths of a US dollar: cache reads at 0.1×, cache writes at 1.25×, plain input at 1×, output at its price. */
	long costMicroUsd(Usage usage, long outputTokens) {
		BigDecimal input = inputPrice.multiply(BigDecimal.valueOf(usage.inputTokens()));
		BigDecimal cacheRead = inputPrice.multiply(BigDecimal.valueOf(usage.cacheReadTokens()))
			.multiply(CACHE_READ_FACTOR);
		BigDecimal cacheCreation = inputPrice.multiply(BigDecimal.valueOf(usage.cacheCreationTokens()))
			.multiply(CACHE_CREATION_FACTOR);
		BigDecimal output = outputPrice.multiply(BigDecimal.valueOf(outputTokens));
		return input.add(cacheRead).add(cacheCreation).add(output).setScale(0, RoundingMode.CEILING).longValueExact();
	}

	private void add(Count count) {
		jdbc.sql("""
				INSERT INTO aisearch.daily_usage AS d (day, %1$s) VALUES (:day, 1)
				ON CONFLICT (day) DO UPDATE SET %1$s = d.%1$s + 1""".formatted(count.column))
			.param("day", today())
			.update();
	}

	private LocalDate today() {
		return LocalDate.ofInstant(clock.instant(), zone);
	}

}
