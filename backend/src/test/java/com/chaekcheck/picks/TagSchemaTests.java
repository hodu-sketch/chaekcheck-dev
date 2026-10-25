package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The tag dictionary and book tags (cycle 23 step 1, ADR-0149 decision #9). */
@SpringBootTest
@Transactional
class TagSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void validRowsAreAccepted() {
		jdbc.sql("INSERT INTO picks.tag_dictionary (code, axis, name, thema_code) VALUES ('m03', 'mood', '잔잔한', NULL),"
				+ " ('FB_1', 'subject', '소설', 'FB'), ('a1', 'audience', '청소년', 'YP')").update();
		jdbc.sql("INSERT INTO picks.book_tag VALUES (11, 'm03', DATE '2026-10-24'), (11, 'FB_1', DATE '2026-10-24')")
			.update();
	}

	@Test
	void rulesHold() {
		violates(() -> dictionary("'m-1'", "'mood'"));
		violates(() -> dictionary("'abcdefghijklmnopqrstu'", "'mood'"));
		violates(() -> dictionary("'m1'", "'genre'"));
		violates(() -> jdbc.sql("INSERT INTO picks.book_tag VALUES (11, 'nope', DATE '2026-10-24')").update());
	}

	private void dictionary(String code, String axis) {
		jdbc.sql("INSERT INTO picks.tag_dictionary (code, axis, name) VALUES (" + code + ", " + axis + ", '이름')").update();
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
