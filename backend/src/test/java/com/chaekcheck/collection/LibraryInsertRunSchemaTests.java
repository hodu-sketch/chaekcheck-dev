package com.chaekcheck.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle 13 (ADR-0084, ADR-0093): the constraints of {@code collection.library_insert_run}
 * (collection/006-library-insert-run.yaml) on db-test. Each violation runs in its own test so the failed statement
 * does not abort the others.
 */
@SpringBootTest
@Transactional
class LibraryInsertRunSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void aSucceededRunGoesIn() {
		insert("0123456789ab", "SUCCEEDED", null);
		assertThat(jdbc.sql("""
				SELECT ran_at IS NOT NULL FROM collection.library_insert_run WHERE report_id = '0123456789ab'""")
			.query(Boolean.class)
			.single()).isTrue();
	}

	@Test
	void reportIdIsTwelveLowercaseHexDigits() {
		assertViolation(() -> insert("0123456789AB", "SUCCEEDED", null));
	}

	@Test
	void reportIdIsNotShorter() {
		assertViolation(() -> insert("0123456789a", "SUCCEEDED", null));
	}

	@Test
	void statusHasTwoValues() {
		assertViolation(() -> insert("0123456789ab", "RUNNING", null));
	}

	@Test
	void aFailedRunNeedsItsDetail() {
		assertViolation(() -> insert("0123456789ab", "FAILED", null));
	}

	@Test
	void aSucceededRunHasNoDetail() {
		assertViolation(() -> insert("0123456789ab", "SUCCEEDED", "detail"));
	}

	@Test
	void insertedCountIsNotNegative() {
		assertViolation(() -> jdbc.sql("""
				INSERT INTO collection.library_insert_run (report_id, snapshot, inserted_count, status, payload)
				VALUES ('0123456789ab', 'snapshot.sqlite', -1, 'SUCCEEDED', '{}')""").update());
	}

	@Test
	void aReportSucceedsOnce() {
		insert("0123456789ab", "SUCCEEDED", null);
		assertViolation(() -> insert("0123456789ab", "SUCCEEDED", null));
	}

	@Test
	void failedRunsOfOneReportMayRepeat() {
		insert("0123456789ab", "FAILED", "first");
		insert("0123456789ab", "FAILED", "second");
		insert("0123456789ab", "SUCCEEDED", null);
		assertThat(jdbc.sql("SELECT count(*) FROM collection.library_insert_run WHERE report_id = '0123456789ab'")
			.query(Long.class)
			.single()).isEqualTo(3);
	}

	private void insert(String reportId, String status, String detail) {
		jdbc.sql("""
				INSERT INTO collection.library_insert_run
				  (report_id, snapshot, inserted_count, join_surveyed_on, status, failure_detail, payload)
				VALUES (:reportId, 'snapshot.sqlite', 0, DATE '2026-10-05', :status, :detail, '{}')""")
			.param("reportId", reportId)
			.param("status", status)
			.param("detail", detail)
			.update();
	}

	private static void assertViolation(Runnable statement) {
		assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
	}

}
