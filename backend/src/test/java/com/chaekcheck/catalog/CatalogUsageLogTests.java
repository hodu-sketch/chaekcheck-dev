package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.usagelog.UsageLogTestTables;
import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;

/**
 * Cycle 15 step 4: the catalog search and book page leave usage rows (AC-1, AC-2, AC-5, AC-6, AC-17 part). Logging is
 * on for this class only; the flush is called by hand and joins the test transaction, so everything rolls back.
 */
@SpringBootTest(properties = { "chaekcheck.usagelog.enabled=true", "chaekcheck.usagelog.flush-interval=1h" })
@AutoConfigureMockMvc
@Transactional
class CatalogUsageLogTests {

	static final long MAPO = 1114;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UsageLogFlusher flusher;

	long library;

	long book;

	@BeforeEach
	void setUp() {
		UsageLogTestTables.empty(jdbc);
		library = jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method, visit_required)
				VALUES ('테스트 기록', 'KYOBO_V1', 'KYOBO', 'OWNED', 'https://usagelog.test', 'test', false) RETURNING id""")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO library.eligibility_rule (library_id, basis, scope_type) VALUES (?, 'RESIDENCE', 'NATIONWIDE')")
			.param(library)
			.update();
		book = jdbc.sql("""
				INSERT INTO catalog.book (title, author_display, publisher, title_norm, main_title_norm, publisher_norm,
				  published_on, content_type, library_ids)
				VALUES ('채식주의자 기록시험', '한강', '창비', :norm, :main, :pub, DATE '2007-10-30', 'EBOOK',
				  CAST(:ids AS bigint[]))
				RETURNING id""")
			.param("norm", CatalogText.titleNorm("채식주의자 기록시험"))
			.param("main", CatalogText.mainTitleNorm("채식주의자 기록시험"))
			.param("pub", CatalogText.publisherNorm("창비"))
			.param("ids", new Long[] { library })
			.query(Long.class)
			.single();
		jdbc.sql("""
				INSERT INTO catalog.holding (book_id, library_id, platform, item_key, detail_url, content_type, first_seen_at)
				VALUES (?, ?, 'KYOBO_V1', 'usagelog-item', 'https://usagelog.test/detail', 'EBOOK', now())""")
			.params(book, library)
			.update();
	}

	@Test
	void firstPageIsOneSearchRow() { // AC-1
		assertThat(mvc.get()
			.uri("/api/v1/books?title=  채식주의자   기록시험 &exact=title&roles=translator&sort=newest"
					+ "&residenceRegionIds=" + MAPO + "&libraryIds=" + library)
			.header("X-Real-IP", "203.0.113.7")).hasStatusOk();
		flusher.flush();

		Map<String, Object> row = jdbc.sql("""
				SELECT kind, title, author, array_to_string(exact, ',') AS exact, array_to_string(roles, ',') AS roles,
				  sort, filter, scope, visit, array_to_string(residence_region_ids, ',') AS residence,
				  array_to_string(work_region_ids, ',') AS work, array_to_string(library_ids, ',') AS libraries,
				  result_count, login_status, host(client_ip) AS ip
				FROM usagelog.search_event""").query().singleRow();
		assertThat(row).containsEntry("kind", "catalog")
			.containsEntry("title", "채식주의자 기록시험")
			.containsEntry("author", null)
			.containsEntry("exact", "title")
			.containsEntry("roles", "translator")
			.containsEntry("sort", "newest")
			.containsEntry("filter", null)
			.containsEntry("scope", "all")
			.containsEntry("visit", "any")
			.containsEntry("residence", String.valueOf(MAPO))
			.containsEntry("work", "")
			.containsEntry("libraries", String.valueOf(library))
			.containsEntry("result_count", 1)
			.containsEntry("login_status", "login_off")
			.containsEntry("ip", "203.0.113.7");
		assertThat(UsageLogTestTables.count(jdbc, "search_more")).isZero();
	}

	@Test
	void zeroBooksIsStillASearch() {
		assertThat(mvc.get().uri("/api/v1/books?publisher=없는출판사이름")).hasStatusOk();
		flusher.flush();

		assertThat(jdbc.sql("SELECT result_count FROM usagelog.search_event").query(Integer.class).single()).isZero();
	}

	@Test
	void laterPageIsMore() { // AC-2
		assertThat(mvc.get().uri("/api/v1/books?title=채식주의자&page=2")).hasStatusOk();
		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "search_event")).isZero();
		assertThat(jdbc.sql("SELECT kind, seq, title, sort, library_id FROM usagelog.search_more").query().singleRow())
			.containsEntry("kind", "catalog_next_page")
			.containsEntry("seq", 2)
			.containsEntry("title", "채식주의자")
			.containsEntry("sort", "default")
			.containsEntry("library_id", null);
	}

	@Test
	void rejectedRequestsLeaveNoRow() { // AC-5
		assertThat(mvc.get().uri("/api/v1/books?title=채")).hasStatus(HttpStatus.BAD_REQUEST);
		assertThat(mvc.get().uri("/api/v1/books?title=채식&workRegionIds=11")).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT);
		assertThat(mvc.get().uri("/api/v1/books/999999999")).hasStatus(HttpStatus.NOT_FOUND);
		flusher.flush();

		for (String table : UsageLogTestTables.TABLES) {
			assertThat(UsageLogTestTables.count(jdbc, table)).as(table).isZero();
		}
	}

	@Test
	void robotsGetTheSameAnswerButNoRow() throws Exception { // AC-6
		String person = mvc.get().uri("/api/v1/books?title=채식주의자").exchange().getResponse().getContentAsString(StandardCharsets.UTF_8);
		flusher.flush();
		UsageLogTestTables.empty(jdbc);

		var robot = mvc.get()
			.uri("/api/v1/books?title=채식주의자")
			.header("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)")
			.exchange();
		flusher.flush();

		assertThat(robot.getResponse().getContentAsString(StandardCharsets.UTF_8)).isEqualTo(person);
		assertThat(UsageLogTestTables.count(jdbc, "search_event")).isZero();
	}

	@Test
	void bookPageIsOneView() { // AC-17 part
		assertThat(mvc.get().uri("/api/v1/books/" + book + "?residenceRegionIds=" + MAPO)).hasStatusOk();
		flusher.flush();

		assertThat(jdbc.sql("""
				SELECT book_id, array_to_string(residence_region_ids, ',') AS residence, login_status
				FROM usagelog.book_view""").query().singleRow()).containsEntry("book_id", book)
			.containsEntry("residence", String.valueOf(MAPO))
			.containsEntry("login_status", "login_off");
	}

}
