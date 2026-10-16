package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.usagelog.UsageLog.MoreKind;
import com.chaekcheck.usagelog.UsageLog.ResponseResult;
import com.chaekcheck.usagelog.UsageLog.ResponseSource;
import com.chaekcheck.usagelog.UsageLog.SearchKind;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;
import com.chaekcheck.usagelog.internal.write.UsageLogFlusher;
import com.chaekcheck.usagelog.internal.write.UsageLogWriter;

/**
 * The write path with logging on (cycle 15 step 3): one flush writes every table in one go, library answers add up,
 * a failed batch is dropped and noted (AC-19), unknown or disabled libraries are rejected (plan C5). The scheduled
 * flush is pushed out of the way; the tests flush by hand.
 */
@SpringBootTest(properties = { "chaekcheck.usagelog.enabled=true", "chaekcheck.usagelog.flush-interval=1h" })
// the same context as the other logging-on classes (CatalogUsageLogTests, UsageEventApiTests): db-test has 100 connections
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
class UsageLogWriteTests {

	static final Instant AT = Instant.parse("2026-10-14T03:00:00Z");

	static final SearchConditions CATALOG = SearchConditions.catalog("채식주의자", null, null, List.of("title"),
			List.of(), "default", "all", "any", List.of(1114L), List.of(), List.of(12L));

	@Autowired
	JdbcClient jdbc;

	@Autowired
	UsageBuffer buffer;

	@Autowired
	UsageLogWriter writer;

	@Autowired
	UsageLogFlusher flusher;

	@Autowired
	LibraryCatalog libraryCatalog;

	@Autowired
	UsageLog usageLogBean;

	long enabledLibrary;

	@BeforeEach
	void emptyTables() {
		UsageLogTestTables.empty(jdbc);
		buffer.drain();
		enabledLibrary = libraryCatalog.enabledLibraryRefs().getFirst().id();
	}

	@Test
	void oneFlushWritesEveryTable() {
		buffer.offer(new UsageRow.Search(AT, "catalog", CATALOG, 3, "signed_in", "203.0.113.7"));
		buffer.offer(new UsageRow.BookView(AT, 345, List.of(1114L), List.of(), List.of(), "signed_out", null));
		buffer.offer(new UsageRow.More(AT, "catalog_next_page", 2, null, CATALOG, "login_off", "2001:db8::1"));
		buffer.offer(new UsageRow.LibraryClick(AT, enabledLibrary, "result_card", "book_page", 345L, "signed_in",
				"203.0.113.7", "{}"));
		buffer.offer(new UsageRow.ScreenEvent(AT, "region_change", null, null, null, List.of(1114L), List.of(1102L), null,
				"signed_in", "203.0.113.7", "{}"));
		buffer.offer(new UsageRow.Rejected(AT, "203.0.113.7", "malformed", null));
		buffer.addResponse(LocalDate.of(2026, 10, 14), enabledLibrary, ResponseSource.DIRECT_SEARCH,
				ResponseResult.OK, 250);

		flusher.flush();

		for (String table : List.of("search_event", "book_view", "search_more", "library_click", "screen_event",
				"rejected_event", "library_response_daily")) {
			assertThat(UsageLogTestTables.count(jdbc, table)).as(table).isEqualTo(1);
		}
		assertThat(jdbc.sql("""
				SELECT kind, title, exact, roles, sort, filter, scope, visit, residence_region_ids, library_ids,
				  result_count, login_status, host(client_ip) AS ip, received_at
				FROM usagelog.search_event""").query().singleRow()).containsEntry("kind", "catalog")
			.containsEntry("title", "채식주의자")
			.containsEntry("sort", "default")
			.containsEntry("filter", null)
			.containsEntry("result_count", 3)
			.containsEntry("login_status", "signed_in")
			.containsEntry("ip", "203.0.113.7");
		assertThat(jdbc.sql("SELECT array_to_string(exact, ',') FROM usagelog.search_event").query(String.class)
			.single()).isEqualTo("title");
		assertThat(jdbc.sql("SELECT received_at FROM usagelog.search_event").query(Instant.class).single())
			.isEqualTo(AT);
		assertThat(jdbc.sql("SELECT host(client_ip) FROM usagelog.search_more").query(String.class).single())
			.isEqualTo("2001:db8::1");
		assertThat(UsageLogTestTables.count(jdbc, "dropped_batch")).isZero();
	}

	@Test
	void libraryAnswersAddToTheDayLine() { // AC-24 (write part)
		LocalDate day = LocalDate.of(2026, 10, 14);
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.OK, 100);
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.TIMEOUT, 8000);
		flusher.flush();
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.OK, 700);
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.SKIPPED, 0);
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.FAILED, 20);
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.CACHED, 9000);
		flusher.flush();
		buffer.addResponse(day, enabledLibrary, ResponseSource.HOLDING_FILL, ResponseResult.CACHED, 0);
		flusher.flush();

		Map<String, Object> line = jdbc.sql("""
				SELECT ok_count, failed_count, timeout_count, skipped_count, ok_ms_total, ok_ms_max, cached_count
				FROM usagelog.library_response_daily WHERE day = ? AND library_id = ? AND source = 'holding_fill'""")
			.params(day, enabledLibrary)
			.query()
			.singleRow();
		assertThat(line).containsEntry("ok_count", 2)
			.containsEntry("failed_count", 1)
			.containsEntry("timeout_count", 1)
			.containsEntry("skipped_count", 1)
			.containsEntry("ok_ms_total", 800L)
			.containsEntry("ok_ms_max", 700)
			.containsEntry("cached_count", 2);
	}

	@Test
	void failedBatchIsDroppedAndNotedWithTheNextOne(CapturedOutput output) { // AC-19
		AtomicBoolean failNext = new AtomicBoolean(true);
		UsageLogWriter failingOnce = new UsageLogWriter(null, null) {
			@Override
			public void write(List<UsageRow> rows, Map<UsageBuffer.ResponseKey, UsageBuffer.ResponseTotals> responses,
					List<UsageLogWriter.Dropped> dropped) {
				if (failNext.getAndSet(false)) {
					throw new DataAccessResourceFailureException("connection refused");
				}
				writer.write(rows, responses, dropped);
			}
		};
		UsageLogFlusher flush = new UsageLogFlusher(buffer, failingOnce, libraryCatalog, Clock.systemUTC(),
				Duration.ofHours(1), 100, false);
		buffer.offer(new UsageRow.Search(AT, "catalog", CATALOG, 3, "signed_in", null));
		buffer.offer(new UsageRow.BookView(AT, 345, List.of(), List.of(), List.of(), "signed_out", null));
		buffer.addResponse(LocalDate.of(2026, 10, 14), enabledLibrary, ResponseSource.DIRECT_SEARCH,
				ResponseResult.OK, 250);

		flush.flush();

		assertThat(UsageLogTestTables.count(jdbc, "search_event")).isZero();
		assertThat(output).contains("WARN").contains("Usage log write failed, 3 rows dropped");

		buffer.offer(new UsageRow.Search(AT, "catalog", CATALOG, 4, "signed_in", null));
		flush.flush();

		assertThat(UsageLogTestTables.count(jdbc, "search_event")).isEqualTo(1);
		assertThat(UsageLogTestTables.count(jdbc, "book_view")).isZero();
		assertThat(UsageLogTestTables.count(jdbc, "library_response_daily")).isZero();
		assertThat(jdbc.sql("SELECT row_count, reason FROM usagelog.dropped_batch").query().singleRow())
			.containsEntry("row_count", 3)
			.containsEntry("reason", "db_failure");
	}

	@Test
	void fullBufferIsNotedAsBufferFull() {
		UsageBuffer small = new UsageBuffer(1);
		UsageLogFlusher flush = new UsageLogFlusher(small, writer, libraryCatalog, Clock.systemUTC(),
				Duration.ofHours(1), 100, false);
		small.offer(new UsageRow.Rejected(AT, null, "malformed", null));
		small.offer(new UsageRow.Rejected(AT, null, "malformed", null));
		small.offer(new UsageRow.Rejected(AT, null, "malformed", null));

		flush.flush();

		assertThat(UsageLogTestTables.count(jdbc, "rejected_event")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT row_count, reason FROM usagelog.dropped_batch").query().singleRow())
			.containsEntry("row_count", 2)
			.containsEntry("reason", "buffer_full");
	}

	@Test
	void unknownAndDisabledLibrariesAreRejected() { // plan C5
		long disabled = jdbc.sql("SELECT id FROM library.library WHERE NOT enabled ORDER BY id LIMIT 1")
			.query(Long.class)
			.single();
		buffer.offer(new UsageRow.LibraryClick(AT, enabledLibrary, "logo", "picks", null, "login_off", "203.0.113.7",
				"{}"));
		buffer.offer(new UsageRow.LibraryClick(AT, disabled, "logo", "picks", null, "login_off", "203.0.113.7",
				"{\"libraryId\":" + disabled + "}"));
		buffer.offer(new UsageRow.LibraryClick(AT, 999_999_999L, "logo", "picks", null, "login_off", "203.0.113.7",
				"{\"libraryId\":999999999}"));
		buffer.offer(new UsageRow.ScreenEvent(AT, "my_library_add", null, null, null, null, null, 999_999_999L,
				"login_off", "203.0.113.7", "{\"libraryId\":999999999}"));

		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "library_click")).isEqualTo(1);
		assertThat(UsageLogTestTables.count(jdbc, "screen_event")).isZero();
		assertThat(jdbc.sql("""
				SELECT count(*) FROM usagelog.rejected_event
				WHERE reason = 'unknown_library' AND host(client_ip) = '203.0.113.7' AND raw_values ->> 'libraryId' IS NOT NULL""")
			.query(Long.class)
			.single()).isEqualTo(3);
	}

	@Test
	void usageLogApiQueuesForTheFlush() {
		UsageRequest request = new UsageRequest(AT, "203.0.113.7", "signed_out", true);
		UsageLog usageLog = usageLogBean;
		usageLog.recordSearch(request, SearchKind.CATALOG, CATALOG, 0);
		usageLog.recordMore(request, MoreKind.LIBRARY_MORE, 2, enabledLibrary,
				SearchConditions.direct("채식주의자", null, null, null, null, null, null, null, null));

		flusher.flush();

		assertThat(UsageLogTestTables.count(jdbc, "search_event")).isEqualTo(1);
		assertThat(jdbc.sql("SELECT kind, seq, library_id, scope, filter FROM usagelog.search_more").query().singleRow())
			.containsEntry("kind", "library_more")
			.containsEntry("seq", 2)
			.containsEntry("library_id", enabledLibrary)
			.containsEntry("scope", null)
			.containsEntry("filter", null);
	}

}
