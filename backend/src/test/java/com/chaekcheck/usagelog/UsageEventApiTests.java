package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;

/**
 * {@code POST /api/v1/usage-events} with login off (the default) and logging on (cycle 15 step 6): always 204, no
 * CSRF token needed with login off (AC-9 off part), good reports become rows without a member number (AC-23 server
 * part, AC-7), bad ones become {@code rejected_event} rows with the IP and reason (AC-10).
 */
@SpringBootTest(properties = { "chaekcheck.usagelog.enabled=true", "chaekcheck.usagelog.flush-interval=1h" })
@AutoConfigureMockMvc
@Transactional
class UsageEventApiTests {

	static final String URL = "/api/v1/usage-events";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UsageLogFlusher flusher;

	@Autowired
	LibraryCatalog libraryCatalog;

	long library;

	@BeforeEach
	void setUp() {
		UsageLogTestTables.empty(jdbc);
		library = libraryCatalog.enabledLibraryRefs().getFirst().id();
	}

	@Test
	void libraryClickIsOneRow() { // AC-7, AC-9 (login off: no token)
		post("""
				{"type":"library_click","libraryId":%d,"place":"result_card","screen":"picks","bookId":345}"""
			.formatted(library));
		post("""
				{"type":"library_click","libraryId":%d,"place":"logo","screen":"direct_results"}""".formatted(library));
		flusher.flush();

		assertThat(jdbc.sql("""
				SELECT place, screen, book_id, login_status, host(client_ip) AS ip FROM usagelog.library_click
				ORDER BY id""").query().listOfRows()).containsExactly(
					Map.of("place", "result_card", "screen", "picks", "book_id", 345L, "login_status", "login_off",
							"ip", "203.0.113.7"),
					row("logo", "direct_results"));
		assertThat(UsageLogTestTables.count(jdbc, "rejected_event")).isZero();
	}

	@Test
	void screenActionsAreOneRowEach() { // AC-23 (server part)
		post("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"11\"}");
		post("{\"type\":\"picks_book_view\",\"list\":\"snu100\",\"category\":\"korean_lit\",\"bookId\":345}");
		post("{\"type\":\"region_change\",\"residenceRegionIds\":[1114],\"workRegionIds\":[]}");
		post("{\"type\":\"my_library_add\",\"libraryId\":%d}".formatted(library));
		post("{\"type\":\"my_library_remove\",\"libraryId\":%d}".formatted(library));
		flusher.flush();

		assertThat(jdbc.sql("""
				SELECT kind, picks_list, picks_category, book_id, array_to_string(residence_region_ids, ',') AS residence,
				  array_to_string(work_region_ids, ',') AS work, library_id
				FROM usagelog.screen_event ORDER BY id""").query().listOfRows()).hasSize(5)
			.extracting(r -> r.get("kind") + "|" + r.get("picks_list") + "|" + r.get("picks_category") + "|"
					+ r.get("book_id") + "|" + r.get("residence") + "|" + r.get("work") + "|" + r.get("library_id"))
			.containsExactly("picks_list_view|librarian|11|null|null|null|null",
					"picks_book_view|snu100|korean_lit|345|null|null|null",
					"region_change|null|null|null|1114||null", "my_library_add|null|null|null|null|null|" + library,
					"my_library_remove|null|null|null|null|null|" + library);
		assertThat(UsageLogTestTables.count(jdbc, "rejected_event")).isZero();
	}

	@Test
	void visitKeyIsKeptWhenWellFormedAndNullOtherwise() { // ADR-0134 (present, absent, wrong shape); login off: no member
		String key = "a1b2c3d4e5f60718a1b2c3d4e5f60718";
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\",\"visitKey\":\"%s\"}"
			.formatted(library, key));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\"}".formatted(library));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\",\"visitKey\":\"short\"}"
			.formatted(library));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\",\"visitKey\":12345}"
			.formatted(library));
		post("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"11\",\"visitKey\":\"%s\"}".formatted(key));
		post("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"11\",\"visitKey\":\"%s\"}"
			.formatted("a1b2c3d4-e5f6-0718-a1b2-c3d4e5f60718"));
		flusher.flush();

		assertThat(jdbc.sql("SELECT visit_key, account_id FROM usagelog.library_click ORDER BY id").query().listOfRows())
			.extracting(r -> r.get("visit_key") + "|" + r.get("account_id"))
			.containsExactly(key + "|null", "null|null", "null|null", "null|null");
		assertThat(jdbc.sql("SELECT visit_key, account_id FROM usagelog.screen_event ORDER BY id").query().listOfRows())
			.extracting(r -> r.get("visit_key") + "|" + r.get("account_id"))
			.containsExactly(key + "|null", "null|null");
		assertThat(UsageLogTestTables.count(jdbc, "rejected_event")).isZero();
	}

	@Test
	void badReportsAreRejectedWithTheirReason() { // AC-10
		long disabled = jdbc.sql("SELECT id FROM library.library WHERE NOT enabled ORDER BY id LIMIT 1")
			.query(Long.class)
			.single();
		post("{\"type\":\"library_click\",\"libraryId\":999999999,\"place\":\"logo\",\"screen\":\"picks\"}");
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\"}".formatted(disabled));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"cover\",\"screen\":\"picks\"}".formatted(library));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"home\"}".formatted(library));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\",\"bookId\":-3}"
			.formatted(library));
		post("{\"type\":\"library_click\",\"libraryId\":%d,\"place\":\"logo\",\"screen\":\"picks\",\"bookId\":\"7\"}"
			.formatted(library));
		post("{not json");
		post("[1,2]");
		post("");
		post("{\"type\":\"page_scroll\"}");
		post("{\"libraryId\":1}");
		post("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"\"}");
		post("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"123456789012345678901\"}");
		// cycle 21 (ADR-0126): the list is required and one of three
		post("{\"type\":\"picks_list_view\",\"category\":\"11\"}");
		post("{\"type\":\"picks_book_view\",\"list\":\"yes24\",\"category\":\"11\",\"bookId\":345}");
		post("{\"type\":\"region_change\",\"residenceRegionIds\":[1114]}");
		post("{\"type\":\"region_change\",\"residenceRegionIds\":[0],\"workRegionIds\":[]}");
		post("{\"type\":\"my_library_add\",\"libraryId\":\"12\"}");
		post("{\"type\":\"my_library_add\",\"libraryId\":999999999}");
		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "library_click")).isZero();
		assertThat(UsageLogTestTables.count(jdbc, "screen_event")).isZero();
		assertThat(jdbc.sql("""
				SELECT reason FROM usagelog.rejected_event
				WHERE host(client_ip) = '203.0.113.7' ORDER BY id""").query(String.class).list())
			.containsExactlyInAnyOrder("unknown_library", "unknown_library", "invalid_place", "invalid_screen",
					"invalid_book_id", "invalid_book_id", "malformed", "malformed", "malformed", "unknown_type",
					"unknown_type", "invalid_value", "invalid_value", "invalid_value", "invalid_value", "invalid_value",
					"invalid_value", "invalid_value", "unknown_library");
		assertThat(jdbc.sql("SELECT raw_values ->> 'place' FROM usagelog.rejected_event WHERE reason = 'invalid_place'")
			.query(String.class)
			.single()).isEqualTo("cover");
	}

	@Test
	void largeValuesAreCutTo2KB() {
		String big = "{\"type\":\"page_scroll\",\"note\":\"" + "가".repeat(1500) + "\"}";
		post(big);
		post("x".repeat(20_000));
		post("{\"type\":\"picks_list_view\",\"category\":\"a\\u0000b\"}");
		flusher.flush();

		assertThat(jdbc.sql("SELECT octet_length(raw_values #>> '{}') FROM usagelog.rejected_event "
				+ "WHERE jsonb_typeof(raw_values) = 'string' ORDER BY id")
			.query(Integer.class)
			.list()).hasSize(3).allSatisfy(length -> assertThat(length).isLessThanOrEqualTo(2048));
		assertThat(jdbc.sql("SELECT reason FROM usagelog.rejected_event ORDER BY id").query(String.class).list())
			.containsExactly("unknown_type", "malformed", "invalid_value");
	}

	@Test
	void robotsAreNotRecorded() { // C7 (usage reports too)
		assertThat(mvc.post()
			.uri(URL)
			.contentType(MediaType.APPLICATION_JSON)
			.header("User-Agent", "Googlebot/2.1")
			.content("{\"type\":\"picks_list_view\",\"list\":\"librarian\",\"category\":\"11\"}"))
			.hasStatus(HttpStatus.NO_CONTENT);
		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "screen_event")).isZero();
	}

	private void post(String body) {
		assertThat(mvc.post()
			.uri(URL)
			.contentType(MediaType.TEXT_PLAIN)
			.header("X-Real-IP", "203.0.113.7")
			.content(body)).hasStatus(HttpStatus.NO_CONTENT).body().isEmpty();
	}

	private static Map<String, Object> row(String place, String screen) {
		java.util.HashMap<String, Object> row = new java.util.HashMap<>();
		row.put("place", place);
		row.put("screen", screen);
		row.put("book_id", null);
		row.put("login_status", "login_off");
		row.put("ip", "203.0.113.7");
		return row;
	}

}
