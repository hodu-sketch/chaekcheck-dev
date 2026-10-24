package com.chaekcheck.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The foryou tables' constraints (cycle 23 step 1, design-delta "데이터"). */
@SpringBootTest
@Transactional
class ForYouSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void validRowsAreAccepted() {
		long run = run("'succeeded'");
		row(run, "'kyobo_elib'", "NULL", "1", "3", "0");
		row(run, "'kpipa_rank'", "'week'", "100", "NULL", "NULL");
		jdbc.sql("INSERT INTO foryou.book_score VALUES (?, 11, 0)").param(run).update();
		jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, 1, 11, true), (?, 40, 12, false)").params(run, run).update();
		neighbours("11", "ARRAY[1]");
		neighbours("12", "ARRAY[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20]");
		jdbc.sql("INSERT INTO foryou.book_blurb VALUES (11, '해요체 한 문장이에요.', DATE '2026-10-24')").update();
		jdbc.sql("INSERT INTO foryou.book_cover VALUES (11, '0123456789abcdef0123456789abcdef01234567')").update();
		assertThat(jdbc.sql("SELECT count(*) FROM foryou.base_entry").query(Long.class).single()).isEqualTo(2);
	}

	@Test
	void runAndRowRulesHold() {
		violates(() -> run("'done'"));
		long run = run("'running'");
		violates(() -> row(run, "'aladin'", "NULL", "1", "NULL", "NULL"));
		violates(() -> row(run, "'kyobo_elib'", "'year'", "1", "NULL", "NULL"));
		violates(() -> row(run, "'kyobo_elib'", "NULL", "0", "NULL", "NULL"));
		violates(() -> row(run, "'kyobo_elib'", "NULL", "1", "-1", "NULL"));
		violates(() -> row(run, "'kyobo_elib'", "NULL", "1", "NULL", "-1"));
		violates(() -> jdbc.sql("INSERT INTO foryou.book_score VALUES (?, 11, -0.5)").param(run).update());
		violates(() -> jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, 41, 11, true)").param(run).update());
		violates(() -> jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, 0, 11, true)").param(run).update());
		violates(() -> jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, 1, 11, true), (?, 2, 11, false)")
			.params(run, run)
			.update());
	}

	@Test
	void neighbourBlurbAndCoverRulesHold() {
		violates(() -> neighbours("11", "ARRAY[]::integer[]"));
		violates(() -> neighbours("11",
				"ARRAY[1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20,21]"));
		violates(() -> neighbours("11", "ARRAY[[1,2],[3,4]]"));
		violates(() -> jdbc.sql("INSERT INTO foryou.book_blurb VALUES (11, '', DATE '2026-10-24')").update());
		violates(() -> jdbc.sql("INSERT INTO foryou.book_blurb VALUES (11, repeat('가', 301), DATE '2026-10-24')")
			.update());
		violates(() -> jdbc.sql("INSERT INTO foryou.book_cover VALUES (11, 'abc')").update());
		violates(() -> jdbc.sql("INSERT INTO foryou.book_cover VALUES (11, '0123456789ABCDEF0123456789abcdef01234567')")
			.update());
	}

	@Test
	void deletingARunDeletesItsRows() {
		long run = run("'failed'");
		row(run, "'yes24_elib'", "NULL", "1", "0", "0");
		jdbc.sql("INSERT INTO foryou.book_score VALUES (?, 11, 1)").param(run).update();
		jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, 1, 11, true)").param(run).update();
		jdbc.sql("DELETE FROM foryou.build_run WHERE id = ?").param(run).update();
		for (String table : new String[] { "popular_row", "book_score", "base_entry" }) {
			assertThat(jdbc.sql("SELECT count(*) FROM foryou." + table + " WHERE run_id = ?")
				.param(run)
				.query(Long.class)
				.single()).as(table).isZero();
		}
	}

	private long run(String status) {
		return jdbc.sql("INSERT INTO foryou.build_run (built_on, started_at, status) VALUES (DATE '2026-10-24', now(), "
				+ status + ") RETURNING id")
			.query(Long.class)
			.single();
	}

	private void row(long run, String source, String period, String rank, String loans, String reservations) {
		jdbc.sql("INSERT INTO foryou.popular_row (run_id, source, site_library_id, period, rank, loans_now, reservations,"
				+ " title) VALUES (?, " + source + ", 7, " + period + ", " + rank + ", " + loans + ", " + reservations
				+ ", '책')")
			.param(run)
			.update();
	}

	private void neighbours(String bookId, String ids) {
		jdbc.sql("INSERT INTO foryou.book_neighbour VALUES (" + bookId + ", " + ids + ", DATE '2026-10-24')").update();
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

}
