package com.chaekcheck.usagelog.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.usagelog.UsageLogTestTables;
import com.chaekcheck.usagelog.internal.UsageLogProperties;

/** The daily clean-up with a fixed clock (cycle 15 step 7): AC-13, AC-18, rejected 30 days, pieces. */
@SpringBootTest
@Transactional
class UsageLogCleanupTests {

	/** 2026-10-14 04:30 in Seoul. */
	static final Instant NOW = Instant.parse("2026-10-13T19:30:00Z");

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	JdbcClient jdbc;

	UsageLogCleanup cleanup;

	@BeforeEach
	void setUp() {
		UsageLogTestTables.empty(jdbc);
		cleanup = new UsageLogCleanup(jdbcTemplate, new UsageLogProperties(false, 10_000, Duration.ofSeconds(5), 100,
				new UsageLogProperties.Cleanup("0 30 4 * * *", ZoneId.of("Asia/Seoul"), 3)),
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void rowsGoOneYearAfterTheyCame() { // AC-13
		long old = bookView(NOW.minus(Duration.ofDays(366)), "203.0.113.7");
		long kept = bookView(NOW.minus(Duration.ofDays(364)), null);
		jdbc.sql("INSERT INTO usagelog.dropped_batch (dropped_at, row_count, reason) VALUES (?, 1, 'db_failure')")
			.param(Timestamp.from(NOW.minus(Duration.ofDays(366))))
			.update();
		jdbc.sql("INSERT INTO usagelog.library_response_daily (day, library_id, source) VALUES (?, 1, 'holding_fill')")
			.param(LocalDate.of(2025, 10, 13))
			.update();
		jdbc.sql("INSERT INTO usagelog.library_response_daily (day, library_id, source) VALUES (?, 1, 'holding_fill')")
			.param(LocalDate.of(2025, 10, 14))
			.update();

		UsageLogCleanup.Result result = cleanup.run();

		assertThat(ids("book_view")).containsExactly(kept).doesNotContain(old);
		assertThat(UsageLogTestTables.count(jdbc, "dropped_batch")).isZero();
		assertThat(jdbc.sql("SELECT day FROM usagelog.library_response_daily").query(LocalDate.class).list())
			.containsExactly(LocalDate.of(2025, 10, 14));
		assertThat(result.rowsDeleted()).isEqualTo(3);
	}

	@Test
	void ipIsClearedAfterThirtyDays() { // AC-18
		long month = bookView(NOW.minus(Duration.ofDays(31)), "203.0.113.7");
		long recent = bookView(NOW.minus(Duration.ofDays(29)), "203.0.113.8");

		UsageLogCleanup.Result result = cleanup.run();

		assertThat(ip(month)).isNull();
		assertThat(ip(recent)).isEqualTo("203.0.113.8");
		assertThat(jdbc.sql("SELECT book_id FROM usagelog.book_view WHERE id = ?").param(month).query(Long.class)
			.single()).isEqualTo(7);
		assertThat(result.ipCleared()).isEqualTo(1);
	}

	@Test
	void rejectedReportsGoAfterThirtyDays() {
		rejected(NOW.minus(Duration.ofDays(31)));
		long recent = rejected(NOW.minus(Duration.ofDays(29)));

		cleanup.run();

		assertThat(ids("rejected_event")).containsExactly(recent);
	}

	@Test
	void manyRowsGoInPieces() {
		for (int i = 0; i < 10; i++) {
			bookView(NOW.minus(Duration.ofDays(400)), "203.0.113.7");
			bookView(NOW.minus(Duration.ofDays(40)), "203.0.113.7");
		}

		UsageLogCleanup.Result result = cleanup.run();

		assertThat(result.rowsDeleted()).isEqualTo(10);
		assertThat(result.ipCleared()).isEqualTo(20);
		assertThat(UsageLogTestTables.count(jdbc, "book_view")).isEqualTo(10);
		assertThat(jdbc.sql("SELECT count(*) FROM usagelog.book_view WHERE client_ip IS NOT NULL").query(Long.class)
			.single()).isZero();
	}

	private long bookView(Instant at, String ip) {
		return jdbc.sql("""
				INSERT INTO usagelog.book_view (received_at, book_id, login_status, client_ip)
				VALUES (?, 7, 'login_off', ?::inet) RETURNING id""")
			.params(Timestamp.from(at), ip)
			.query(Long.class)
			.single();
	}

	private long rejected(Instant at) {
		return jdbc.sql("""
				INSERT INTO usagelog.rejected_event (received_at, client_ip, reason)
				VALUES (?, '203.0.113.7', 'malformed') RETURNING id""")
			.param(Timestamp.from(at))
			.query(Long.class)
			.single();
	}

	private java.util.List<Long> ids(String table) {
		return jdbc.sql("SELECT id FROM usagelog." + table + " ORDER BY id").query(Long.class).list();
	}

	private String ip(long id) {
		return jdbc.sql("SELECT host(client_ip) FROM usagelog.book_view WHERE id = ?")
			.param(id)
			.query(String.class)
			.optional()
			.orElse(null);
	}

}
