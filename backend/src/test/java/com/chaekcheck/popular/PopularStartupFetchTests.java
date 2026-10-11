package com.chaekcheck.popular;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;

/**
 * With fetching on and nothing stored, the application fetches at start-up on its own thread and records it (AC-13).
 * Only this class turns fetching on, with the fake site (plan cycle 10 C3). The start-up fetch commits, so the rows
 * it wrote are deleted afterwards; db-test's popular tables are empty otherwise (every other test rolls back). The
 * context is closed after the class: no other class uses it, and its connections count against db-test's limit.
 */
@SpringBootTest(properties = "chaekcheck.popular.fetch.enabled=true")
@Import(FakeKpipaSite.class)
@DirtiesContext
class PopularStartupFetchTests {

	@Autowired
	FakeKpipaSite site;

	@Autowired
	JdbcClient jdbc;

	Long runId;

	@Test
	void startUpWithNothingStoredFetchesLastMonth() throws InterruptedException {
		Map<String, Object> run = waitForCovers(Duration.ofSeconds(60)).orElseThrow();
		runId = (Long) run.get("id");

		assertThat(run).containsEntry("trigger", "STARTUP")
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("request_count", 34)
			.containsEntry("book_count", 850)
			.containsEntry("cover_fetched_count", 50);
		assertThat(site.requests()).hasSize(34 + 50);
		assertThat(jdbc.sql("SELECT count(*) FROM popular.ranking_entry WHERE target_month = :month")
			.param("month", run.get("target_month"))
			.query(Long.class)
			.single()).isEqualTo(850);
	}

	@AfterEach
	void deleteWhatTheStartUpFetchWrote() {
		if (runId == null) {
			return;
		}
		jdbc.sql("""
				DELETE FROM popular.cover v USING popular.ranking_entry e, popular.ranking_month m
				WHERE m.fetch_run_id = :run AND e.target_month = m.target_month AND e.cover_path = v.source_path""")
			.param("run", runId)
			.update();
		jdbc.sql("DELETE FROM popular.ranking_month WHERE fetch_run_id = :run").param("run", runId).update();
		jdbc.sql("DELETE FROM popular.fetch_run WHERE id = :run").param("run", runId).update();
	}

	private Optional<Map<String, Object>> waitForCovers(Duration timeout) throws InterruptedException {
		Instant end = Instant.now().plus(timeout);
		while (Instant.now().isBefore(end)) {
			Optional<Map<String, Object>> run = jdbc.sql("SELECT * FROM popular.fetch_run WHERE trigger = 'STARTUP'")
				.query()
				.listOfRows()
				.stream()
				.findFirst();
			if (run.isPresent()) {
				runId = (Long) run.get().get("id");
				if (((Integer) run.get().get("cover_fetched_count")) > 0) {
					return run;
				}
			}
			Thread.sleep(200);
		}
		return Optional.empty();
	}

}
