package com.chaekcheck.picks;

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
 * Only this class turns fetching on, with the fake library and a test key (plan cycle 14 C3). The start-up fetch
 * commits, so everything it wrote is deleted afterwards; db-test's picks tables are empty otherwise (every other test
 * rolls back). The context is closed after the class: no other class uses it, and its connections count against
 * db-test's limit.
 */
@SpringBootTest(properties = { "chaekcheck.picks.fetch.enabled=true",
		"chaekcheck.picks.fetch.api-key=test-secret-key-123" })
@Import(FakeNlk.class)
@DirtiesContext
class PicksStartupFetchTests {

	@Autowired
	FakeNlk nlk;

	@Autowired
	JdbcClient jdbc;

	@Test
	void startUpWithNothingStoredFetches() throws InterruptedException {
		Map<String, Object> run = waitForTheRun(Duration.ofSeconds(60)).orElseThrow();

		assertThat(run).containsEntry("trigger", "STARTUP")
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("changed", true)
			.containsEntry("kept_count", 108);
		assertThat(nlk.requests().getFirst().getPath()).isEqualTo("/NL/search/openApi/saseoApi.do");
		assertThat(jdbc.sql("SELECT count(*) FROM picks.list_book").query(Long.class).single()).isEqualTo(108);
	}

	@AfterEach
	void deleteWhatTheStartUpFetchWrote() {
		jdbc.sql("DELETE FROM picks.list_book WHERE list_key = 'librarian'").update();
		jdbc.sql("DELETE FROM picks.list_field WHERE list_key = 'librarian' AND position > 5").update();
		jdbc.sql("DELETE FROM picks.fetch_run").update();
	}

	private Optional<Map<String, Object>> waitForTheRun(Duration timeout) throws InterruptedException {
		Instant end = Instant.now().plus(timeout);
		while (Instant.now().isBefore(end)) {
			Optional<Map<String, Object>> run = jdbc.sql("SELECT * FROM picks.fetch_run WHERE trigger = 'STARTUP'")
				.query()
				.listOfRows()
				.stream()
				.findFirst();
			if (run.isPresent()) {
				return run;
			}
			Thread.sleep(200);
		}
		return Optional.empty();
	}

}
