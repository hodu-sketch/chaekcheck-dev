package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The eight usage log tables and their CHECK rules (cycle 15 step 2, design-delta "데이터"), and AC-12. */
@SpringBootTest
@Transactional
class UsageLogSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void eightTables() {
		assertThat(jdbc.sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'usagelog'")
			.query(String.class)
			.list()).containsExactlyInAnyOrder("search_event", "book_view", "search_more", "library_click",
					"screen_event", "library_response_daily", "rejected_event", "dropped_batch");
	}

	@Test
	void noMemberNumberUserAgentOrCookieColumn() { // AC-12
		assertThat(jdbc.sql("""
				SELECT count(*) FROM information_schema.columns
				WHERE table_schema = 'usagelog'
				  AND (column_name ~ '(user|account|member)' OR column_name ~ '(agent|cookie|token|session)')""")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void validRowsAreAccepted() {
		search("'catalog'", "'채식주의자'", "'{}'", "'{}'", "'default'", "NULL", "'signed_in'");
		search("'direct'", "'채식주의자'", "NULL", "NULL", "NULL", "'available'", "'login_off'");
		more("'library_more'", "12", "NULL", "NULL", "NULL", "'all'");
		more("'catalog_next_page'", "NULL", "'{title}'", "'{}'", "'newest'", "NULL");
		more("'direct_next_batch'", "NULL", "NULL", "NULL", "NULL", "'all'");
		click("'result_card'", "'picks'", "'signed_out'");
		screen("'picks_list_view'", "'11'", "NULL", "NULL", "NULL", "NULL");
		screen("'picks_book_view'", "'11'", "345", "NULL", "NULL", "NULL");
		screen("'region_change'", "NULL", "NULL", "'{1114}'", "'{}'", "NULL");
		screen("'my_library_add'", "NULL", "NULL", "NULL", "NULL", "12");
		jdbc.sql("INSERT INTO usagelog.rejected_event (received_at, client_ip, reason, raw_values) "
				+ "VALUES (now(), '203.0.113.7', 'unknown_library', '{\"libraryId\":9}')").update();
		jdbc.sql("INSERT INTO usagelog.dropped_batch (dropped_at, row_count, reason) VALUES (now(), 3, 'db_failure')")
			.update();
		jdbc.sql("INSERT INTO usagelog.library_response_daily (day, library_id, source) "
				+ "VALUES (DATE '2026-10-14', 1, 'holding_fill')").update();
	}

	@Test
	void searchRulesHold() {
		violates(() -> search("'popular'", "'채식'", "'{}'", "'{}'", "'default'", "NULL", "'signed_in'"));
		violates(() -> search("'catalog'", "NULL", "'{}'", "'{}'", "'default'", "NULL", "'signed_in'"));
		violates(() -> search("'catalog'", "'채식'", "'{}'", "'{}'", "'default'", "NULL", "'unknown'"));
		violates(() -> search("'catalog'", "'채식'", "NULL", "'{}'", "'default'", "NULL", "'signed_in'"));
		violates(() -> search("'catalog'", "'채식'", "'{}'", "'{}'", "'default'", "'all'", "'signed_in'"));
		violates(() -> search("'direct'", "'채식'", "'{}'", "NULL", "NULL", "'all'", "'signed_in'"));
		violates(() -> search("'direct'", "'채식'", "NULL", "NULL", "NULL", "NULL", "'signed_in'"));
		violates(() -> search("'direct'", "'채식'", "NULL", "NULL", "NULL", "'some'", "'signed_in'"));
	}

	@Test
	void moreRulesHold() {
		violates(() -> more("'next'", "NULL", "NULL", "NULL", "NULL", "'all'"));
		violates(() -> more("'library_more'", "NULL", "NULL", "NULL", "NULL", "'all'"));
		violates(() -> more("'direct_next_batch'", "12", "NULL", "NULL", "NULL", "'all'"));
		violates(() -> more("'catalog_next_page'", "NULL", "NULL", "NULL", "NULL", "NULL"));
		violates(() -> more("'direct_next_batch'", "NULL", "'{}'", "'{}'", "'default'", "'all'"));
	}

	@Test
	void clickRulesHold() {
		violates(() -> click("'cover'", "'picks'", "'signed_in'"));
		violates(() -> click("'result_card'", "'home'", "'signed_in'"));
		violates(() -> click("'result_card'", "'picks'", "'maybe'"));
	}

	@Test
	void screenEventFillsOnlyItsOwnColumns() {
		violates(() -> screen("'picks_scroll'", "'11'", "NULL", "NULL", "NULL", "NULL"));
		violates(() -> screen("'picks_list_view'", "NULL", "NULL", "NULL", "NULL", "NULL"));
		violates(() -> screen("'picks_list_view'", "'11'", "345", "NULL", "NULL", "NULL"));
		violates(() -> screen("'picks_book_view'", "'11'", "NULL", "NULL", "NULL", "NULL"));
		violates(() -> screen("'region_change'", "NULL", "NULL", "'{1114}'", "NULL", "NULL"));
		violates(() -> screen("'region_change'", "NULL", "NULL", "'{1114}'", "'{}'", "12"));
		violates(() -> screen("'my_library_remove'", "NULL", "NULL", "NULL", "NULL", "NULL"));
		violates(() -> screen("'my_library_add'", "'11'", "NULL", "NULL", "NULL", "12"));
	}

	@Test
	void otherTablesRulesHold() {
		violates(() -> jdbc.sql("INSERT INTO usagelog.rejected_event (received_at, reason) VALUES (now(), 'spam')")
			.update());
		violates(() -> jdbc.sql("INSERT INTO usagelog.dropped_batch (dropped_at, row_count, reason) "
				+ "VALUES (now(), 1, 'timeout')").update());
		violates(() -> jdbc.sql("INSERT INTO usagelog.library_response_daily (day, library_id, source) "
				+ "VALUES (DATE '2026-10-14', 1, 'collector')").update());
		violates(() -> jdbc.sql("INSERT INTO usagelog.library_response_daily (day, library_id, source, ok_count) "
				+ "VALUES (DATE '2026-10-14', 1, 'direct_search', -1)").update());
	}

	private void search(String kind, String title, String exact, String roles, String sort, String filter,
			String loginStatus) {
		jdbc.sql("""
				INSERT INTO usagelog.search_event (received_at, kind, title, exact, roles, sort, filter, scope, visit,
				  login_status, client_ip)
				VALUES (now(), %s, %s, %s, %s, %s, %s, 'all', 'any', %s, '203.0.113.7')"""
			.formatted(kind, title, exact, roles, sort, filter, loginStatus)).update();
	}

	private void more(String kind, String libraryId, String exact, String roles, String sort, String filter) {
		jdbc.sql("""
				INSERT INTO usagelog.search_more (received_at, kind, seq, library_id, title, exact, roles, sort, filter,
				  login_status)
				VALUES (now(), %s, 2, %s, '채식주의자', %s, %s, %s, %s, 'signed_out')"""
			.formatted(kind, libraryId, exact, roles, sort, filter)).update();
	}

	private void click(String place, String screen, String loginStatus) {
		jdbc.sql("""
				INSERT INTO usagelog.library_click (received_at, library_id, place, screen, login_status)
				VALUES (now(), 12, %s, %s, %s)""".formatted(place, screen, loginStatus)).update();
	}

	private void screen(String kind, String category, String bookId, String residence, String work,
			String libraryId) {
		jdbc.sql("""
				INSERT INTO usagelog.screen_event (received_at, kind, picks_category, book_id, residence_region_ids,
				  work_region_ids, library_id, login_status)
				VALUES (now(), %s, %s, %s, %s, %s, %s, 'login_off')"""
			.formatted(kind, category, bookId, residence, work, libraryId)).update();
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
