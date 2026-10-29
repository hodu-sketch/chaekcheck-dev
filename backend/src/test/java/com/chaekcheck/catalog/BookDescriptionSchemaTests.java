package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The catalog book description tables (cycle 26 step 2, catalog/002, ADR-0161, ADR-0166). */
@SpringBootTest
@Transactional
class BookDescriptionSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void validRowsAreAccepted() {
		jdbc.sql("INSERT INTO catalog.book_summary VALUES (11, '해요체 한 문장이에요.', DATE '2026-10-28'),"
				+ " (12, repeat('가', 300), DATE '2026-10-28')").update();
		jdbc.sql("INSERT INTO catalog.book_cover VALUES (11, '0123456789abcdef0123456789abcdef01234567')").update();
		jdbc.sql("INSERT INTO catalog.book_profile VALUES (11, '문학', '한국소설', true, ARRAY['성장'], '한국', '성인',"
				+ " '보통', ARRAY['장편'])").update();
		jdbc.sql("INSERT INTO catalog.book_profile (book_id) VALUES (12)").update();
		jdbc.sql("INSERT INTO catalog.book_search (book_id, line, line_source, line_made_on, search_text, library_ids)"
				+ " VALUES (11, '한 줄이에요.', 'intro', DATE '2026-10-28', '제목 한 줄이에요.', ARRAY[1, 2])").update();
		jdbc.sql("INSERT INTO catalog.book_search (book_id, search_text) VALUES (12, '제목')").update();
		jdbc.sql("INSERT INTO catalog.tag_dictionary (code, axis, name, thema_code) VALUES ('m03', 'mood', '잔잔한', NULL)")
			.update();
		jdbc.sql("INSERT INTO catalog.book_tag VALUES (11, 'm03', DATE '2026-10-28')").update();
		assertThat(jdbc.sql("SELECT genres FROM catalog.book_profile WHERE book_id = 12").query(String.class).single())
			.isEqualTo("{}");
	}

	@Test
	void summaryAndCoverRulesHold() {
		violates(() -> jdbc.sql("INSERT INTO catalog.book_summary VALUES (11, '', DATE '2026-10-28')").update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_summary VALUES (11, repeat('가', 301), DATE '2026-10-28')")
			.update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_summary VALUES (0, '요약이에요.', DATE '2026-10-28')").update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_summary (book_id, summary) VALUES (11, '요약이에요.')").update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_cover VALUES (11, 'abc')").update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_cover VALUES (11, '0123456789ABCDEF0123456789abcdef01234567')")
			.update());
	}

	@Test
	void searchLineRulesHold() {
		violates(() -> search("'한 줄'", "NULL", "NULL"));
		violates(() -> search("NULL", "'intro'", "NULL"));
		violates(() -> search("NULL", "NULL", "DATE '2026-10-28'"));
		violates(() -> search("'한 줄'", "'intro'", "NULL"));
		violates(() -> search("''", "'intro'", "DATE '2026-10-28'"));
		violates(() -> search("repeat('가', 301)", "'intro'", "DATE '2026-10-28'"));
		violates(() -> search("'한 줄'", "'ai'", "DATE '2026-10-28'"));
		violates(() -> jdbc.sql("INSERT INTO catalog.book_search (book_id, search_text) VALUES (11, NULL)").update());
	}

	@Test
	void tagRulesHold() {
		violates(() -> jdbc.sql("INSERT INTO catalog.tag_dictionary (code, axis, name) VALUES ('m-1', 'mood', '이름')")
			.update());
		violates(() -> jdbc.sql("INSERT INTO catalog.tag_dictionary (code, axis, name) VALUES ('m1', 'genre', '이름')")
			.update());
		violates(() -> jdbc.sql("INSERT INTO catalog.book_tag VALUES (11, 'nope', DATE '2026-10-28')").update());
	}

	@Test
	void searchTextKeepsTheBigmIndex() {
		assertThat(jdbc.sql("SELECT indexdef FROM pg_indexes WHERE schemaname = 'catalog'"
				+ " AND indexname = 'book_search_search_text_bigm'").query(String.class).single())
			.contains("gin_bigm_ops");
		assertThat(jdbc.sql("SELECT collation_name FROM information_schema.columns WHERE table_schema = 'catalog'"
				+ " AND table_name = 'book_search' AND column_name = 'search_text'").query(String.class).single())
			.isEqualTo("C");
	}

	@Test
	void importsCountTheDeletedDescriptions() {
		// collection/007: nullable for the imports before it, never negative
		assertThat(jdbc.sql("SELECT is_nullable FROM information_schema.columns WHERE table_schema = 'collection'"
				+ " AND table_name = 'catalog_import' AND column_name = 'description_deleted_count'")
			.query(String.class)
			.single()).isEqualTo("YES");
		assertThat(jdbc.sql("SELECT pg_get_constraintdef(oid) FROM pg_constraint"
				+ " WHERE conname = 'catalog_import_description_deleted_count_check'").query(String.class).single())
			.contains("description_deleted_count >= 0");
	}

	@Test
	void theOldTablesAreDropped() {
		// cycle 26 deploy 2 (foryou/002, picks/007, aisearch/005): the copied tables and the stored covers are gone
		for (String table : new String[] { "foryou.book_blurb", "foryou.book_cover", "picks.book_tag",
				"picks.tag_dictionary", "picks.cover", "aisearch.search_book" }) {
			assertThat(jdbc.sql("SELECT to_regclass(:table) IS NULL").param("table", table).query(Boolean.class).single())
				.as(table)
				.isTrue();
		}
	}

	private void search(String line, String source, String madeOn) {
		jdbc.sql("INSERT INTO catalog.book_search (book_id, line, line_source, line_made_on, search_text) VALUES (11, "
				+ line + ", " + source + ", " + madeOn + ", '제목')").update();
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
