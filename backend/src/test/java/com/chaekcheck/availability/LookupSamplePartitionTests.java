package com.chaekcheck.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.availability.internal.ops.JdbcOpsSampleStore;
import com.chaekcheck.availability.internal.ops.OpsSample;
import com.chaekcheck.availability.internal.ops.OpsSampler;

/**
 * availability.lookup_sample against db-test (user decision 2026-10-19 "파티션 잘 하고"): range partitioned by
 * sampled_at, one partition per Asia/Seoul month plus DEFAULT, ensure / drop functions. Each test rolls back
 * (PostgreSQL DDL is transactional), so the month partitions made by the migration stay as they were.
 */
@SpringBootTest
@Transactional
class LookupSamplePartitionTests {

	static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

	static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("'lookup_sample_y'yyyy'm'MM");

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationContext context;

	@Test
	void rangePartitionedBySampledAtWithADefaultPartition() {
		assertThat(jdbc.sql("""
				SELECT p.partstrat::text || ':' || a.attname FROM pg_partitioned_table p
				JOIN pg_attribute a ON a.attrelid = p.partrelid AND a.attnum = p.partattrs[0]
				WHERE p.partrelid = 'availability.lookup_sample'::regclass""").query(String.class).single())
			.isEqualTo("r:sampled_at");
		assertThat(jdbc.sql("""
				SELECT c.relname FROM pg_inherits i JOIN pg_class c ON c.oid = i.inhrelid
				WHERE i.inhparent = 'availability.lookup_sample'::regclass
				  AND pg_get_expr(c.relpartbound, c.oid) = 'DEFAULT'""").query(String.class).single())
			.isEqualTo("lookup_sample_default");
		assertThat(jdbc.sql("""
				SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indrelid = 'availability.lookup_sample'::regclass
				  AND indisprimary""").query(String.class).single()).contains("(sampled_at)");
	}

	@Test
	void ensureMakesTheCurrentMonthAndTwoMoreInSeoulMonths() {
		assertThat(jdbc.sql("SELECT availability.lookup_sample_ensure_partitions(2)").query(Integer.class).single())
			.isBetween(0, 3); // 0 when the migration (this month) or an earlier run already made them
		LocalDate thisMonth = LocalDate.now(SEOUL).withDayOfMonth(1);
		for (int i = 0; i <= 2; i++) {
			LocalDate month = thisMonth.plusMonths(i);
			Instant lower = month.atStartOfDay(SEOUL).toInstant();
			Instant upper = month.plusMonths(1).atStartOfDay(SEOUL).toInstant();
			assertThat(partitionOf(lower)).isEqualTo(NAME.format(month));
			assertThat(partitionOf(upper.minus(1, ChronoUnit.MICROS))).isEqualTo(NAME.format(month));
		}
		// three months ahead is not made yet: such a row goes to DEFAULT, and ensure(3) moves it into its month
		LocalDate third = thisMonth.plusMonths(3);
		Instant inThird = third.atStartOfDay(SEOUL).toInstant().plusSeconds(3600);
		assertThat(partitionOf(inThird)).isEqualTo("lookup_sample_default");
		assertThat(jdbc.sql("SELECT availability.lookup_sample_ensure_partitions(3)").query(Integer.class).single())
			.isEqualTo(1);
		assertThat(jdbc.sql("SELECT tableoid::regclass::text FROM availability.lookup_sample WHERE sampled_at = ?")
			.param(Timestamp.from(inThird))
			.query(String.class)
			.single()).isEqualTo("availability." + NAME.format(third));
	}

	@Test
	void theStoreWritesARowIntoTheMonthPartition() {
		Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		new JdbcOpsSampleStore(jdbc).insert(new OpsSample(now, 10_000, 60, 3, 7, 1, 120, 0, 2, 8, 1, 5, 2, 0, 210,
				null, "ALL", 120, 0, 0, 0));
		assertThat(jdbc.sql("""
				SELECT tableoid::regclass::text || ' ' || window_ms || ' ' || slots_max || ' ' || slots_max_in_use || ' '
				  || library_requests || ' ' || db_errors || ' ' || parse_max_in_use || ' '
				  || coalesce(heap_max_mb::text, 'null') || ' ' || egress_mode
				FROM availability.lookup_sample WHERE sampled_at = ?""")
			.param(Timestamp.from(now))
			.query(String.class)
			.single()).isEqualTo("availability." + NAME.format(LocalDate.ofInstant(now, SEOUL)) + " 10000 60 7 120 2 5 null ALL");
	}

	@Test
	void dropRemovesWholeMonthsOlderThanTheCutoffAndOldDefaultRows() {
		jdbc.sql("""
				CREATE TABLE availability.lookup_sample_y2001m01 PARTITION OF availability.lookup_sample
				FOR VALUES FROM ('2001-01-01 00:00+09') TO ('2001-02-01 00:00+09')""").update();
		jdbc.sql("""
				CREATE TABLE availability.lookup_sample_y2001m02 PARTITION OF availability.lookup_sample
				FOR VALUES FROM ('2001-02-01 00:00+09') TO ('2001-03-01 00:00+09')""").update();
		insertAt(Instant.parse("2001-01-15T00:00:00Z"));
		insertAt(Instant.parse("2001-02-15T00:00:00Z"));
		insertAt(Instant.parse("2000-06-15T00:00:00Z")); // DEFAULT
		insertAt(Instant.parse("2000-12-31T16:00:00Z")); // 2001-01-01 01:00 KST, so the January partition
		assertThat(partitionOf(Instant.parse("2000-06-15T00:00:00Z"))).isEqualTo("lookup_sample_default");

		// a cutoff inside February keeps February whole
		Instant cutoff = ZonedDateTime.of(2001, 2, 10, 0, 0, 0, 0, SEOUL).toInstant();
		assertThat(jdbc.sql("SELECT availability.lookup_sample_drop_before(?)")
			.param(Timestamp.from(cutoff))
			.query(Integer.class)
			.single()).isEqualTo(1);
		assertThat(exists("availability.lookup_sample_y2001m01")).isFalse();
		assertThat(exists("availability.lookup_sample_y2001m02")).isTrue();
		assertThat(jdbc.sql("SELECT count(*) FROM availability.lookup_sample WHERE sampled_at < '2001-03-01'")
			.query(Long.class)
			.single()).isEqualTo(1);
		// the current month is never dropped by the 90-day cutoff
		assertThat(jdbc.sql("SELECT availability.lookup_sample_drop_before(now() - interval '90 days')")
			.query(Integer.class)
			.single()).isGreaterThanOrEqualTo(1); // February 2001 at least
		assertThat(partitionOf(Instant.now())).isEqualTo(NAME.format(LocalDate.now(SEOUL)));
	}

	@Test
	void theSamplerIsOffInTheTestProfile() {
		assertThat(context.getBeanNamesForType(OpsSampler.class)).isEmpty();
	}

	private void insertAt(Instant at) {
		new JdbcOpsSampleStore(jdbc).insert(new OpsSample(at, 10_000, 60, 0, 0, 0, 0, 0, 0, 8, 0, 0, 0, 0, 100, 512,
				"ALL", 0, 0, 0, 0));
	}

	/** The partition a row at {@code at} lands in, by inserting one (rolled back with the test). */
	private String partitionOf(Instant at) {
		jdbc.sql("""
				INSERT INTO availability.lookup_sample (sampled_at, window_ms, slots_max, slots_in_use, slots_max_in_use,
				  refused, library_requests, body_too_large, db_errors, parse_max, parse_in_use, parse_max_in_use,
				  queued_libraries, paused_lanes, heap_used_mb, egress_mode, lane_requests, lane_pauses, lane_retests,
				  lane_resumes)
				VALUES (?, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 'ALL', 0, 0, 0, 0) ON CONFLICT DO NOTHING""")
			.param(Timestamp.from(at))
			.update();
		return jdbc.sql("SELECT tableoid::regclass::text FROM availability.lookup_sample WHERE sampled_at = ?")
			.param(Timestamp.from(at))
			.query(String.class)
			.single()
			.replace("availability.", "");
	}

	private boolean exists(String table) {
		return jdbc.sql("SELECT to_regclass(?) IS NOT NULL").param(table).query(Boolean.class).single();
	}

}
