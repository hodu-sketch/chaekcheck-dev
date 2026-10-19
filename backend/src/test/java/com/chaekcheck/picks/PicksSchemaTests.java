package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.Yaml;

/**
 * The picks tables' constraints (plan cycle 14 step 2, design-delta "데이터"), popular gone (AC-22), and since cycle 21
 * the list tables (handoff step 1-1, ADR-0122) and moving the librarian picks over from {@code picks.pick} (AC-10).
 */
@SpringBootTest
@Transactional
class PicksSchemaTests {

	static final String CHANGELOG = "db/changelog/picks/002-curated-lists.yaml";

	@Autowired
	JdbcClient jdbc;

	@Test
	void popularSchemaAndItsChangesetsAreGone() { // AC-22
		assertThat(count("SELECT count(*) FROM information_schema.schemata WHERE schema_name = 'popular'")).isZero();
		assertThat(count("SELECT count(*) FROM databasechangelog WHERE id LIKE 'popular-001-%'")).isZero();
		assertThat(count("SELECT count(*) FROM databasechangelog WHERE id LIKE 'picks-001-%'")).isEqualTo(4);
	}

	@Test
	void pickIsGoneAndTheKnownLibrarianFieldsAreThere() {
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'picks'"
				+ " AND table_name = 'pick'")).isZero();
		assertThat(jdbc.sql("""
				SELECT code || ':' || name FROM picks.list_field WHERE list_key = 'librarian' AND position <= 5
				ORDER BY position""").query(String.class).list())
			.containsExactly("11:문학", "6:인문", "5:사회", "4:자연", "425:테마");
	}

	@Test
	void validRowsAreAccepted() {
		run("'SUCCEEDED', NULL, true");
		run("'FAILED', 'no_books', NULL");
		pick("'20260922163135829100'", "'11'", "DATE '2026-10-01'", "'9791124591055'",
				"'https://www.nl.go.kr/afile/fileDownload/exWmn'");
		pick("'1'", "'425'", "DATE '2016-01-01'", "NULL", "NULL");
		fields();
		leedongjin("DATE '2026-06-01'", "'lMx0zEd1qtY'", "NULL", "'https://image.yes24.com/goods/1/M'");
		leedongjin("DATE '2025-12-01'", "'6fhNgxzKaLE'", "2025", "NULL");
		snu100("3", "'https://dcontent.dkyobobook.co.kr/x.jpg'");
		snu100("4", "NULL");
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
	void librarianRulesHold() {
		violates(() -> pick("'123456789012345678901'", "'11'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'20a'", "'11'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'999'", "DATE '2026-10-01'", "NULL", "NULL")); // no such field
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-02'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'11'", "NULL", "NULL", "NULL"));
		violates(() -> pick("NULL", "'11'", "DATE '2026-10-01'", "NULL", "NULL"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "'9791124591'", "NULL"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "NULL", "'http://www.nl.go.kr/afile/x'"));
		violates(() -> pick("'1'", "'11'", "DATE '2026-10-01'", "NULL", "'https://evil.example/afile/x'"));
		pick("'7'", "'11'", "DATE '2026-10-01'", "NULL", "NULL");
		violates(() -> pick("'7'", "'6'", "DATE '2026-10-01'", "NULL", "NULL")); // one recomNo once
		violates(() -> book("'librarian', '11', 1, '가', DATE '2026-10-01', NULL, NULL, '8', 5, NULL"));
	}

	@Test
	void fieldRulesHold() {
		violates(() -> field("'yes24'", "'x'", "'가'", "1"));
		violates(() -> field("'leedongjin'", "''", "'가'", "1"));
		violates(() -> field("'leedongjin'", "'123456789012345678901'", "'가'", "1"));
		violates(() -> field("'leedongjin'", "'x'", "''", "1"));
		violates(() -> field("'leedongjin'", "'x'", "'가'", "0"));
		field("'leedongjin'", "'x'", "'가'", "1");
		violates(() -> field("'leedongjin'", "'y'", "'나'", "1")); // one place per list
		violates(() -> field("'leedongjin'", "'x'", "'다'", "2")); // one code per list
		field("'snu100'", "'x'", "'가'", "1");
	}

	@Test
	void leedongjinAndSnuRulesHold() {
		fields();
		violates(() -> leedongjin("NULL", "'lMx0zEd1qtY'", "NULL", "NULL"));
		violates(() -> leedongjin("DATE '2026-06-01'", "NULL", "NULL", "NULL"));
		violates(() -> leedongjin("DATE '2026-06-01'", "'has space'", "NULL", "NULL"));
		violates(() -> leedongjin("DATE '2026-06-01'", "'lMx0zEd1qtY'", "1999", "NULL"));
		violates(() -> leedongjin("DATE '2026-06-01'", "'lMx0zEd1qtY'", "NULL", "'http://image.yes24.com/x'"));
		violates(() -> book("'leedongjin', 'korean_fiction', 1, '가', DATE '2026-06-01', 'lMx0zEd1qtY', NULL, '8',"
				+ " NULL, NULL"));
		violates(() -> snu100("NULL", "NULL"));
		violates(() -> snu100("101", "NULL"));
		violates(() -> snu100("5", "'http://x.example/a.jpg'"));
		violates(() -> book("'snu100', 'korean_lit', 1, '가', DATE '2026-06-01', NULL, 6, NULL, NULL, NULL"));
		violates(() -> book("'snu100', 'korean_lit', 1, '가', NULL, 'lMx0zEd1qtY', 6, NULL, NULL, NULL"));
		snu100("6", "NULL");
		violates(() -> snu100("6", "NULL")); // one book per table-of-contents number
		violates(() -> jdbc.sql("UPDATE picks.list_book SET book_id = 0 WHERE list_key = 'snu100'").update());
	}

	@Test
	void kaistRulesHold() { // user 2026-10-19 (picks/003), years 2026-10-20 (picks/004)
		field("'kaist'", "'ai'", "'AI'", "1");
		kaist("1", "'https://image.yes24.com/goods/1/M'");
		kaist("2", "NULL");
		violates(() -> kaist("2", "NULL")); // one book per place in a year
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, title, year_book)"
				+ " VALUES ('kaist', 'ai', 2, '다', 2025)").update(); // the same place in another year
		violates(() -> book("'kaist', 'ai', 5, '가', NULL, NULL, NULL, NULL, NULL, NULL")); // no year
		violates(() -> kaist("0", "NULL"));
		violates(() -> kaist("3", "'http://x.example/a.jpg'"));
		violates(() -> book("'kaist', 'ai', 4, '가', DATE '2026-06-01', NULL, NULL, NULL, NULL, NULL"));
		violates(() -> book("'kaist', 'ai', 4, '가', NULL, 'lMx0zEd1qtY', NULL, NULL, NULL, NULL"));
		violates(() -> book("'kaist', 'ai', 4, '가', NULL, NULL, 6, NULL, NULL, NULL"));
		violates(() -> book("'kaist', 'ai', 4, '가', NULL, NULL, NULL, '8', NULL, NULL"));
		violates(() -> book("'kaist', 'nature', 4, '가', NULL, NULL, NULL, NULL, NULL, NULL")); // no such field
	}

	@Test
	void deletingAFieldDeletesItsBooks() {
		fields();
		snu100("8", "NULL");
		jdbc.sql("DELETE FROM picks.list_field WHERE list_key = 'snu100'").update();
		assertThat(count("SELECT count(*) FROM picks.list_book WHERE list_key = 'snu100'")).isZero();
	}

	@Test
	void oneCoverPerAddress() {
		cover();
		violates(this::cover);
	}

	/**
	 * The changeset's own SQL run again on a {@code picks.pick} of the cycle 14 shape: the librarian rows come out in
	 * the order the cache showed them, with the fields as the fetch named them (AC-10, design-delta Liquibase 2·3).
	 */
	@Test
	void moveKeepsTheOrderAndTheFields() throws IOException {
		jdbc.sql("DELETE FROM picks.list_book WHERE list_key = 'librarian'").update();
		jdbc.sql("DELETE FROM picks.list_field WHERE list_key = 'librarian'").update();
		jdbc.sql("""
				CREATE TABLE picks.pick (recom_no text PRIMARY KEY, dr_code text NOT NULL, dr_code_name text,
				  recom_month date NOT NULL, registered_on date, title text NOT NULL, author text, publisher text,
				  isbn13 text, cover_source text)""").update();
		jdbc.sql("""
				INSERT INTO picks.pick (recom_no, dr_code, dr_code_name, recom_month, registered_on, title, cover_source)
				VALUES ('9', '11', '문학', DATE '2026-10-01', DATE '2026-10-02', '가', NULL),
				  ('10', '11', '문학', DATE '2026-10-01', DATE '2026-10-02', '나', NULL),
				  ('11', '11', '문학', DATE '2026-10-01', NULL, '다', NULL),
				  ('12', '11', '문학', DATE '2026-10-01', DATE '2026-10-05', '라', NULL),
				  ('13', '11', '문학', DATE '2026-08-01', DATE '2026-08-30', '마', 'https://www.nl.go.kr/afile/a'),
				  ('20', '425', NULL, DATE '2026-10-01', NULL, '바', NULL),
				  ('30', '999', '새분야', DATE '2026-10-01', NULL, '사', NULL),
				  ('31', '30', '  ', DATE '2026-10-01', NULL, '아', NULL),
				  ('32', 'abc', NULL, DATE '2026-10-01', NULL, '자', NULL)""").update();

		for (String sql : changesetSql("picks-002-move-librarian")) {
			jdbc.sql(sql).update();
		}

		assertThat(jdbc.sql("""
				SELECT code || ':' || name || ':' || position FROM picks.list_field WHERE list_key = 'librarian'
				ORDER BY position""").query(String.class).list())
			.containsExactly("11:문학:1", "6:인문:2", "5:사회:3", "4:자연:4", "425:테마:5", "30:기타:6", "999:새분야:7",
					"abc:기타:8");
		// month, then registration date (unknown last), then recomNo as text, each latest first
		assertThat(jdbc.sql("""
				SELECT title FROM picks.list_book WHERE list_key = 'librarian' AND field_code = '11' ORDER BY position""")
			.query(String.class)
			.list()).containsExactly("라", "가", "나", "다", "마");
		assertThat(jdbc.sql("""
				SELECT source_no || ':' || position || ':' || coalesce(cover_source, '-') FROM picks.list_book
				WHERE list_key = 'librarian' AND field_code = '11' AND title = '마'""").query(String.class).single())
			.isEqualTo("13:5:https://www.nl.go.kr/afile/a");
		assertThat(count("SELECT count(*) FROM picks.list_book WHERE list_key = 'librarian'")).isEqualTo(9);
		assertThat(count("SELECT count(*) FROM information_schema.tables WHERE table_schema = 'picks'"
				+ " AND table_name = 'pick'")).isZero();
	}

	@SuppressWarnings("unchecked")
	private static List<String> changesetSql(String id) throws IOException {
		try (InputStream in = new ClassPathResource(CHANGELOG).getInputStream()) {
			Map<String, Object> log = new Yaml().load(in);
			List<String> sql = new ArrayList<>();
			for (Map<String, Object> entry : (List<Map<String, Object>>) log.get("databaseChangeLog")) {
				Map<String, Object> changeSet = (Map<String, Object>) entry.get("changeSet");
				if (changeSet != null && id.equals(changeSet.get("id"))) {
					for (Map<String, Object> change : (List<Map<String, Object>>) changeSet.get("changes")) {
						sql.add((String) ((Map<String, Object>) change.get("sql")).get("sql"));
					}
				}
			}
			assertThat(sql).isNotEmpty();
			return sql;
		}
	}

	private void run(String statusReasonChanged) {
		jdbc.sql("INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, failure_reason, changed) "
				+ "VALUES ('STARTUP', now(), now(), " + statusReasonChanged + ")").update();
	}

	private void pick(String recomNo, String drCode, String month, String isbn, String cover) {
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, source_no, recommended_month, title,"
				+ " isbn13, cover_source) VALUES ('librarian', " + drCode + ", 1, " + recomNo + ", " + month + ", '가', "
				+ isbn + ", " + cover + ")").update();
	}

	private void field(String list, String code, String name, String position) {
		jdbc.sql("INSERT INTO picks.list_field (list_key, code, name, position) VALUES (" + list + ", " + code + ", "
				+ name + ", " + position + ")").update();
	}

	private void fields() {
		field("'leedongjin'", "'korean_fiction'", "'한국 소설'", "1");
		field("'snu100'", "'korean_lit'", "'한국문학'", "1");
	}

	private void leedongjin(String month, String videoId, String yearBook, String cover) {
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, video_id,"
				+ " year_book, book_id, cover_source) VALUES ('leedongjin', 'korean_fiction', 1, '가', " + month + ", "
				+ videoId + ", " + yearBook + ", 12, " + cover + ")").update();
	}

	private void snu100(String tocNo, String cover) {
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, title, toc_no, book_id, cover_source)"
				+ " VALUES ('snu100', 'korean_lit', 1, '가', " + tocNo + ", NULL, " + cover + ")").update();
	}

	private void kaist(String position, String cover) {
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, title, isbn13, publisher, book_id,"
				+ " cover_source, year_book) VALUES ('kaist', 'ai', " + position + ", '가', '9791190400657', '나', 12, "
				+ cover + ", 2026)").update();
	}

	/** list, field, position, title, month, video, toc, source_no, book_id, registered_on */
	private void book(String values) {
		jdbc.sql("INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, video_id,"
				+ " toc_no, source_no, book_id, registered_on) VALUES (" + values + ")").update();
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
