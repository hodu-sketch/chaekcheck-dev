package com.chaekcheck.aisearch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;

/**
 * {@link AiSearchBookTexts} against db-test (cycle 24 handoff step 4-2): every listed book stored, lines joined, lines
 * of books not listed skipped and counted, a second run replaces everything, no listed book changes nothing, and the
 * 300-character limit is held by the record and by the database. Everything runs in the test transaction.
 */
@SpringBootTest
@Transactional
class AiSearchBookTextsTests {

	private static final LocalDate MADE_ON = LocalDate.of(2026, 10, 24);

	@Autowired
	AiSearchBookTexts texts;

	@Autowired
	JdbcClient jdbc;

	@Test
	void storesEveryListedBookWithItsLine() {
		Result result = texts.replace(
				List.of(new Book(1, "코스모스", "칼 세이건 지음"), new Book(2, "토지", "박경리"), new Book(3, "채식주의자", null)),
				List.of(new Line(1, Source.INTRO, MADE_ON, "우주와 생명의 역사를 쉽게 풀어 쓴 교양서"),
						new Line(3, Source.BIBLIOGRAPHIC, MADE_ON, "한 여성이 고기를 끊으며 시작되는 연작 소설"),
						new Line(9, Source.INTRO, MADE_ON, "목록에 없는 책")));

		assertThat(result).isEqualTo(new Result(3, 2, 1));
		assertThat(jdbc.sql("""
				SELECT book_id, title, author, line, line_source, line_made_on, search_text
				FROM aisearch.search_book ORDER BY book_id""")
			.query((rs, n) -> tuple(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
					rs.getObject(6, LocalDate.class), rs.getString(7)))
			.list()).containsExactly(
					tuple(1L, "코스모스", "칼 세이건 지음", "우주와 생명의 역사를 쉽게 풀어 쓴 교양서", "intro", MADE_ON,
							"코스모스 칼세이건지음 우주와생명의역사를쉽게풀어쓴교양서"),
					tuple(2L, "토지", "박경리", null, null, null, "토지 박경리"),
					tuple(3L, "채식주의자", null, "한 여성이 고기를 끊으며 시작되는 연작 소설", "bibliographic", MADE_ON,
							"채식주의자 한여성이고기를끊으며시작되는연작소설"));
	}

	@Test
	void aSecondRunReplacesEveryRow() {
		texts.replace(List.of(new Book(1, "코스모스", null), new Book(2, "토지", null)),
				List.of(new Line(1, Source.INTRO, MADE_ON, "옛 한 줄")));

		Result result = texts.replace(List.of(new Book(2, "토지 (개정판)", "박경리")),
				List.of(new Line(2, Source.INTRO, MADE_ON, "새 한 줄")));

		assertThat(result).isEqualTo(new Result(1, 1, 0));
		assertThat(jdbc.sql("SELECT book_id, title, line FROM aisearch.search_book")
			.query((rs, n) -> tuple(rs.getLong(1), rs.getString(2), rs.getString(3)))
			.list()).containsExactly(tuple(2L, "토지 (개정판)", "새 한 줄"));
	}

	@Test
	void noListedBookChangesNothing() {
		texts.replace(List.of(new Book(1, "코스모스", null)), List.of());

		assertThatThrownBy(() -> texts.replace(List.of(), List.of(new Line(1, Source.INTRO, MADE_ON, "한 줄"))))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(jdbc.sql("SELECT book_id FROM aisearch.search_book").query(Long.class).list()).containsExactly(1L);
	}

	@Test
	void aFileWithoutLinesCannotRemoveTheStoredLines() {
		texts.replace(List.of(new Book(1, "코스모스", null)), List.of(new Line(1, Source.INTRO, MADE_ON, "한 줄")));

		assertThatThrownBy(() -> texts.replace(List.of(new Book(1, "코스모스", null), new Book(2, "토지", null)), List.of()))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("kept");
		assertThat(jdbc.sql("SELECT book_id, line FROM aisearch.search_book")
			.query((rs, n) -> tuple(rs.getLong(1), rs.getString(2)))
			.list()).containsExactly(tuple(1L, "한 줄"));
	}

	@Test
	void aBookOrALineTwiceIsRefused() {
		assertThatThrownBy(() -> texts.replace(List.of(new Book(1, "가", null), new Book(1, "가", null)), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> texts.replace(List.of(new Book(1, "가", null)),
				List.of(new Line(1, Source.INTRO, MADE_ON, "하나"), new Line(1, Source.INTRO, MADE_ON, "둘"))))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aLineIsOneToThreeHundredCharactersInTheRecordAndTheDatabase() {
		String longest = "가".repeat(300);
		assertThat(new Line(1, Source.INTRO, MADE_ON, longest).text()).hasSize(300);
		assertThatThrownBy(() -> new Line(1, Source.INTRO, MADE_ON, longest + "가"))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Line(1, Source.INTRO, MADE_ON, "")).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Line(1, Source.INTRO, MADE_ON, " \t ")).isInstanceOf(IllegalArgumentException.class);

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO aisearch.search_book (book_id, title, line, line_source, line_made_on, search_text)
				VALUES (1, '가', :line, 'intro', DATE '2026-10-24', 'x')""").param("line", longest + "가").update())
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void theDailyUsageTableHasItsUpdatedAtTrigger() {
		assertThat(jdbc.sql("""
				SELECT count(*) FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid
				WHERE c.relname = 'daily_usage' AND t.tgname = 'daily_usage_set_updated_at'""").query(Long.class).single())
			.isEqualTo(1L);
	}

}
