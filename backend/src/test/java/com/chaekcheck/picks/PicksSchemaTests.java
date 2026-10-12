package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The picks tables' constraints (plan cycle 14 step 2, design-delta "데이터"), and popular gone (AC-22). */
@SpringBootTest
@Transactional
class PicksSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void popularSchemaAndItsChangesetsAreGone() { // AC-22
		assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'popular'")).isZero();
		assertThat(count("SELECT count(*) FROM databasechangelog WHERE id LIKE 'popular-001-%'")).isZero();
		assertThat(count("SELECT count(*) FROM databasechangelog WHERE id LIKE 'picks-001-%'")).isEqualTo(4);
	}

	@Test
	void validRowsAreAccepted() {
		run("'SUCCEEDED', NULL, true");
		run("'FAILED', 'no_books', NULL");
		pick("'20260922163135829100'", "'11'", "DATE '2026-10-01'", "'9791124591055'",
				"'https://www.nl.go.kr/afile/fileDownload/exWmn'");
		pick("'1'", "'425'", "DATE '2016-01-01'", "NULL", "NULL");
	}

	@Test
	void runRulesHold() {
		violates(() -> run("'FAILED', NULL, NULL"));
		violates(() -> run("'SUCCEEDED', 'no_books', true"));
		violates(() -> run("'SUCCEEDED', NULL, NULL"));
		violates(() -> run("'FAILED', 'no_books', false"));
		violates(() -> run("'FAILED', 'too_few_books', NULL"));
		violates(() -> jdbc.sql("""
				INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, changed)
				VALUES ('MONTHLY', now(), now(), 'SUCCEEDED', true)""").update());
		violates(() -> jdbc.sql("""
				INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, changed, kept_count)
				VALUES ('RETRY', now(), now(), 'SUCCEEDED', true, -1)""").update());
	}

	@Test
	void pickRulesHold() {
		violates(() -> pick("'123456789012345678901'", "'11'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'20a'", "'11'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'1'", "''", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'123456789012345678901'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-02'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "'9791124591'", "NULL"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "NULL", "'http://www.nl.go.kr/afile/x'"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "NULL", "'https://evil.example/afile/x'"));
	}

	@Test
	void oneCoverPerAddress() {
		cover();
		violates(this::cover);
	}

	private void run(String statusReasonChanged) {
		jdbc.sql("INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, failure_reason, changed) "
				+ "VALUES ('STARTUP', now(), now(), " + statusReasonChanged + ")").update();
	}

	private void pick(String recomNo, String drCode, String month, String isbn, String cover) {
		jdbc.sql("INSERT INTO picks.pick (recom_no, dr_code, recom_month, title, isbn13, cover_source) VALUES (" + recomNo
				+ ", " + drCode + ", " + month + ", '가', " + isbn + ", " + cover + ")").update();
	}

	private void cover() {
		jdbc.sql("""
				INSERT INTO picks.cover (source_url, content_type, image, byte_size, fetched_at)
				VALUES ('https://www.nl.go.kr/afile/fileDownload/a', 'image/jpeg', '\\x01', 1, now())""").update();
	}

	private void violates(Runnable insert) {
		assertThatThrownBy(() -> {
			jdbc.sql("SAVEPOINT s").update();
			try {
				insert.run();
			}
			finally {
				jdbc.sql("ROLLBACK TO SAVEPOINT s").update();
			}
		}).isInstanceOf(DataIntegrityViolationException.class);
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

}
