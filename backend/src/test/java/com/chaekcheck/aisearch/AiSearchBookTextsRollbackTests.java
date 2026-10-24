package com.chaekcheck.aisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;

/**
 * {@link AiSearchBookTexts#replace} without an outer transaction, as the import command calls it (cycle 24 step 4
 * review): a run commits, and a run that fails in the database after the delete leaves the rows of the run before.
 * The rows are removed after each test.
 */
@SpringBootTest
class AiSearchBookTextsRollbackTests {

	@Autowired
	AiSearchBookTexts texts;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void clean() {
		jdbc.sql("DELETE FROM aisearch.search_book").update();
	}

	@Test
	void aFailureAfterTheDeleteKeepsTheRowsBefore() {
		texts.replace(List.of(new Book(1, "코스모스", null)),
				List.of(new Line(1, Source.INTRO, LocalDate.of(2026, 10, 24), "커밋된 한 줄")));

		// PostgreSQL refuses a NUL character in text: the insert fails after the delete ran
		assertThatThrownBy(() -> texts.replace(List.of(new Book(2, "토지", null), new Book(3, "널\u0000글자", null)),
				List.of(new Line(2, Source.INTRO, LocalDate.of(2026, 10, 25), "새 한 줄"))))
			.isInstanceOf(DataAccessException.class);

		assertThat(jdbc.sql("SELECT line FROM aisearch.search_book WHERE book_id = 1").query(String.class).list())
			.containsExactly("커밋된 한 줄");
		assertThat(jdbc.sql("SELECT count(*) FROM aisearch.search_book").query(Long.class).single()).isEqualTo(1L);
	}

}
