package com.chaekcheck.usagelog.internal.cleanup;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZonedDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.usagelog.internal.UsageLogProperties;

/**
 * Daily clean-up at 04:30 Asia/Seoul (ADR-0096): IP addresses cleared after 30 days, {@code rejected_event} rows
 * deleted after 30 days, every other row deleted one year after it was received ({@code library_response_daily} by
 * its day). Runs in pieces of {@code batch-size} rows, each its own statement, so no long lock. Runs even when
 * logging is off, so rows from a trial period still go.
 */
@Component
public class UsageLogCleanup {

	private static final Logger log = LoggerFactory.getLogger(UsageLogCleanup.class);

	static final Period IP_KEPT = Period.ofDays(30);

	static final Period REJECTED_KEPT = Period.ofDays(30);

	static final Period KEPT = Period.ofYears(1);

	/** Tables with a client IP and a {@code received_at}; {@code rejected_event} is deleted whole at 30 days. */
	static final List<String> IP_TABLES = List.of("search_event", "book_view", "search_more", "library_click",
			"screen_event");

	/** Tables with a {@code received_at} and no IP: deleted after {@link #KEPT} too (cycle 24 {@code ai_search_event}). */
	static final List<String> NO_IP_TABLES = List.of("ai_search_event");

	private final JdbcTemplate jdbc;

	private final UsageLogProperties properties;

	private final Clock clock;

	@Autowired
	UsageLogCleanup(JdbcTemplate jdbc, UsageLogProperties properties) {
		this(jdbc, properties, Clock.systemUTC());
	}

	UsageLogCleanup(JdbcTemplate jdbc, UsageLogProperties properties, Clock clock) {
		this.jdbc = jdbc;
		this.properties = properties;
		this.clock = clock;
	}

	@Scheduled(cron = "${chaekcheck.usagelog.cleanup.cron}", zone = "${chaekcheck.usagelog.cleanup.zone}")
	void runDaily() {
		try {
			Result result = run();
			log.info("usage log clean-up: ip cleared {}, rejected deleted {}, rows deleted {}", result.ipCleared(),
					result.rejectedDeleted(), result.rowsDeleted());
		}
		catch (RuntimeException ex) {
			log.warn("usage log clean-up failed: {}", ex.toString());
		}
	}

	/** One clean-up; the counts are rows changed. */
	public Result run() {
		ZonedDateTime now = clock.instant().atZone(properties.cleanup().zone());
		Timestamp ipBefore = Timestamp.from(now.minus(IP_KEPT).toInstant());
		Timestamp rejectedBefore = Timestamp.from(now.minus(REJECTED_KEPT).toInstant());
		Timestamp rowsBefore = Timestamp.from(now.minus(KEPT).toInstant());
		LocalDate dayBefore = now.toLocalDate().minus(KEPT);
		int batch = properties.cleanup().batchSize();

		long ipCleared = 0;
		for (String table : IP_TABLES) {
			ipCleared += inPieces("""
					UPDATE usagelog.%1$s SET client_ip = NULL WHERE id IN (
					  SELECT id FROM usagelog.%1$s WHERE client_ip IS NOT NULL AND received_at < ? LIMIT ?)"""
				.formatted(table), ipBefore, batch);
		}
		long rejectedDeleted = inPieces("""
				DELETE FROM usagelog.rejected_event WHERE id IN (
				  SELECT id FROM usagelog.rejected_event WHERE received_at < ? LIMIT ?)""", rejectedBefore, batch);
		long rowsDeleted = 0;
		for (String table : java.util.stream.Stream.concat(IP_TABLES.stream(), NO_IP_TABLES.stream()).toList()) {
			rowsDeleted += inPieces("""
					DELETE FROM usagelog.%1$s WHERE id IN (
					  SELECT id FROM usagelog.%1$s WHERE received_at < ? LIMIT ?)""".formatted(table), rowsBefore,
					batch);
		}
		rowsDeleted += inPieces("""
				DELETE FROM usagelog.dropped_batch WHERE id IN (
				  SELECT id FROM usagelog.dropped_batch WHERE dropped_at < ? LIMIT ?)""", rowsBefore, batch);
		rowsDeleted += inPieces("""
				DELETE FROM usagelog.library_response_daily WHERE (day, library_id, source) IN (
				  SELECT day, library_id, source FROM usagelog.library_response_daily WHERE day < ? LIMIT ?)""",
				dayBefore, batch);
		return new Result(ipCleared, rejectedDeleted, rowsDeleted);
	}

	/** Repeats the statement until a piece changes fewer rows than the batch size. */
	private long inPieces(String sql, Object before, int batch) {
		long total = 0;
		int changed;
		do {
			changed = jdbc.update(sql, before, batch);
			total += changed;
		}
		while (changed >= batch);
		return total;
	}

	public record Result(long ipCleared, long rejectedDeleted, long rowsDeleted) {
	}

}
