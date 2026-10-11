package com.chaekcheck.popular;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The popular schema's constraints (plan cycle 10 design-delta "데이터"). Each violation runs in a savepoint so the
 * test transaction goes on.
 */
@SpringBootTest
@Transactional
class PopularSchemaTests {

	static final String NOVEL = "00000000000000000303";

	@Autowired
	JdbcClient jdbc;

	long runId;

	@BeforeEach
	void month() {
		runId = jdbc.sql("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status)
				VALUES (DATE '1990-01-01', 'STARTUP', now(), now(), 'SUCCEEDED') RETURNING id""")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO popular.ranking_month VALUES (DATE '1990-01-01', :run, now())").param("run", runId).update();
		jdbc.sql("""
				INSERT INTO popular.ranking_category VALUES (DATE '1990-01-01', 'ALL', NULL, 0),
				  (DATE '1990-01-01', :novel, 10, 1)""").param("novel", NOVEL).update();
		jdbc.sql("""
				INSERT INTO popular.ranking_entry VALUES (DATE '1990-01-01', 'ALL', 1, '9791199489561', '가', '철학',
				  '/files/isbn/2026/07/06/s_a.jpg')""").update();
	}

	@Test
	void failureReasonOnlyAndAlwaysWithFailed() {
		violates("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status)
				VALUES (DATE '1990-02-01', 'SCHEDULED', now(), now(), 'FAILED')""");
		violates("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, failure_reason)
				VALUES (DATE '1990-02-01', 'SCHEDULED', now(), now(), 'SUCCEEDED', 'too_few_books')""");
		violates("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, failure_reason)
				VALUES (DATE '1990-02-01', 'SCHEDULED', now(), now(), 'FAILED', 'other')""");
		violates("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status)
				VALUES (DATE '1990-02-02', 'SCHEDULED', now(), now(), 'SUCCEEDED')""");
		violates("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, request_count)
				VALUES (DATE '1990-02-01', 'SCHEDULED', now(), now(), 'SUCCEEDED', -1)""");
		assertThat(jdbc.sql("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, failure_reason,
				  failure_detail)
				VALUES (DATE '1990-02-01', 'SCHEDULED', now(), now(), 'FAILED', 'too_few_books', '소설 49권')""")
			.update()).isEqualTo(1);
	}

	@Test
	void rankAndDisplayOrderRanges() {
		violates(entry(0, "9791199489561", "/a"));
		violates(entry(51, "9791199489561", "/a"));
		assertThat(jdbc.sql(entry(50, null, null)).update()).isEqualTo(1);
		violates("INSERT INTO popular.ranking_category VALUES (DATE '1990-01-01', '00000000000000000223', 1, 17)");
		violates("INSERT INTO popular.ranking_category VALUES (DATE '1990-01-01', '00000000000000000223', 1, 1)");
		violates("INSERT INTO popular.ranking_category VALUES (DATE '1990-01-01', '00000000000000000223', NULL, 2)");
		violates("INSERT INTO popular.ranking_category VALUES (DATE '1990-01-01', '223', 1, 2)");
	}

	@Test
	void isbnAndCoverPath() {
		violates(entry(2, "979-11-994895-6-1", null));
		violates(entry(2, "9771199489561", null));
		violates(entry(2, null, "files/isbn/s_a.jpg"));
		jdbc.sql("""
				INSERT INTO popular.cover (source_path, content_type, image, byte_size, fetched_at)
				VALUES ('/files/isbn/s_a.jpg', 'image/jpeg', decode('00', 'hex'), 1, now())""").update();
		violates("""
				INSERT INTO popular.cover (source_path, content_type, image, byte_size, fetched_at)
				VALUES ('/files/isbn/s_a.jpg', 'image/jpeg', decode('00', 'hex'), 1, now())""");
	}

	@Test
	void deletingTheMonthDeletesItsCategoriesAndBooks() {
		jdbc.sql("DELETE FROM popular.ranking_month WHERE target_month = DATE '1990-01-01'").update();

		assertThat(jdbc.sql("SELECT count(*) FROM popular.ranking_category WHERE target_month = DATE '1990-01-01'")
			.query(Long.class)
			.single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM popular.ranking_entry WHERE target_month = DATE '1990-01-01'")
			.query(Long.class)
			.single()).isZero();
	}

	private String entry(int rank, String isbn, String coverPath) {
		return "INSERT INTO popular.ranking_entry VALUES (DATE '1990-01-01', '" + NOVEL + "', " + rank + ", "
				+ (isbn == null ? "NULL" : "'" + isbn + "'") + ", '제목', NULL, "
				+ (coverPath == null ? "NULL" : "'" + coverPath + "'") + ")";
	}

	private void violates(String sql) {
		jdbc.sql("SAVEPOINT violation").update();
		assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(DataIntegrityViolationException.class);
		jdbc.sql("ROLLBACK TO SAVEPOINT violation").update();
	}

}
