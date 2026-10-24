package com.chaekcheck.aisearch.internal.quota;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;
import com.chaekcheck.aisearch.internal.llm.LlmClient.Usage;
import com.chaekcheck.aisearch.internal.quota.InMemoryAiSearchQuotaTests.SetClock;

/**
 * {@link DailyBudget} against db-test (cycle 24 handoff step 7, AC-9): the cost is added per call in millionths of a
 * dollar, a search that starts after the cap is refused, a new instance (a restart) keeps the day's cost, the next
 * Seoul day starts at zero, and a broken call counts its output as its cap. Runs in the test transaction.
 */
@SpringBootTest
@Transactional
class DailyBudgetTests {

	@Autowired
	JdbcClient jdbc;

	@Autowired
	AiSearchProperties properties;

	/** 2026-10-24 23:59 in Seoul. */
	private final SetClock clock = new SetClock(Instant.parse("2026-10-24T14:59:00Z"));

	@Test
	void costIsTheTokensAtTheirPricesRoundedUp() {
		DailyBudget budget = new DailyBudget(jdbc, properties, clock);

		// 0.10 and 0.50 US$ per million tokens: one input token is 0.1 millionths of a dollar
		assertThat(budget.costMicroUsd(1_000_000, 1_000_000)).isEqualTo(600_000);
		assertThat(budget.costMicroUsd(1_888, 353)).isEqualTo(366);
		assertThat(budget.costMicroUsd(1, 0)).isEqualTo(1);
	}

	@Test
	void aSearchStartingAfterTheCapIsRefusedAndARestartKeepsTheCost() {
		DailyBudget budget = new DailyBudget(jdbc, properties, clock);
		assertThat(budget.allowsStart()).isTrue();

		budget.countRequest();
		budget.addCall(new Usage(1_000_000, 3_000_000, true), 2048); // US$0.10 + US$1.50
		assertThat(budget.allowsStart()).isTrue();
		budget.addCall(new Usage(0, 1_000_000, true), 2048); // + US$0.50 = US$2.10
		DailyBudget restarted = new DailyBudget(jdbc, properties, clock);

		assertThat(restarted.allowsStart()).isFalse();
		assertThat(row(LocalDate.of(2026, 10, 24))).isEqualTo("1|2|1000000|4000000|2100000|0|0");
		clock.add(Duration.ofMinutes(1));
		assertThat(restarted.allowsStart()).isTrue();
	}

	@Test
	void aBrokenCallCountsItsOutputAsItsCapAndRefusalsAreCounted() {
		DailyBudget budget = new DailyBudget(jdbc, properties, clock);

		budget.addCall(new Usage(1_800, 0, false), 2048);
		budget.countRateLimited();
		budget.countPaused();
		budget.countPaused();
		budget.flushRefusals();

		assertThat(row(LocalDate.of(2026, 10, 24))).isEqualTo("0|1|1800|2048|1204|1|2");
	}

	private String row(LocalDate day) {
		return jdbc.sql("""
				SELECT concat_ws('|', request_count, llm_call_count, input_tokens, output_tokens, cost_micro_usd,
				  rate_limited_count, paused_count) FROM aisearch.daily_usage WHERE day = :day""")
			.param("day", day)
			.query(String.class)
			.single();
	}

}
