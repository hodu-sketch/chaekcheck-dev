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
 * dollar, rounded up: input tokens × input price + output tokens × output price, prices per million tokens.
 */
@Component
public class DailyBudget {

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
				INSERT INTO aisearch.daily_usage AS d (day, llm_call_count, input_tokens, output_tokens, cost_micro_usd)
				VALUES (:day, 1, :input, :output, :cost)
				ON CONFLICT (day) DO UPDATE SET llm_call_count = d.llm_call_count + 1,
				  input_tokens = d.input_tokens + EXCLUDED.input_tokens,
				  output_tokens = d.output_tokens + EXCLUDED.output_tokens,
				  cost_micro_usd = d.cost_micro_usd + EXCLUDED.cost_micro_usd""")
			.param("day", today())
			.param("input", (long) usage.inputTokens())
			.param("output", output)
			.param("cost", costMicroUsd(usage.inputTokens(), output))
			.update();
		return output;
	}

	/** Millionths of a US dollar for the tokens, rounded up. */
	long costMicroUsd(long inputTokens, long outputTokens) {
		return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
			.add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
			.setScale(0, RoundingMode.CEILING)
			.longValueExact();
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
