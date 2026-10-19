package com.chaekcheck.availability.internal.ops;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@link OpsSampleStore} on PostgreSQL. The partition functions live in the database
 * ({@code db/changelog/availability/002-lookup-sample-partitions.sql}). Each call is its own statement in
 * auto-commit; nothing here joins a caller's transaction.
 */
public final class JdbcOpsSampleStore implements OpsSampleStore {

	private final JdbcClient jdbc;

	public JdbcOpsSampleStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void insert(OpsSample s) {
		jdbc.sql("""
				INSERT INTO availability.lookup_sample (sampled_at, window_ms, slots_max, slots_in_use,
				  slots_max_in_use, refused, library_requests, body_too_large, db_errors, parse_max, parse_in_use,
				  parse_max_in_use, queued_libraries, paused_lanes, heap_used_mb, heap_max_mb, egress_mode,
				  lane_requests, lane_pauses, lane_retests, lane_resumes)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT DO NOTHING""")
			.params(Timestamp.from(s.sampledAt()), s.windowMs(), s.slotsMax(), s.slotsInUse(), s.slotsMaxInUse(),
					s.refused(), s.libraryRequests(), s.bodyTooLarge(), s.dbErrors(), s.parseMax(), s.parseInUse(),
					s.parseMaxInUse(), s.queuedLibraries(), s.pausedLanes(), s.heapUsedMb(), s.heapMaxMb(),
					s.egressMode(), s.laneRequests(), s.lanePauses(), s.laneRetests(), s.laneResumes())
			.update();
	}

	@Override
	public int ensurePartitions(int monthsAhead) {
		return jdbc.sql("SELECT availability.lookup_sample_ensure_partitions(?)")
			.param(monthsAhead)
			.query(Integer.class)
			.single();
	}

	@Override
	public int dropBefore(Instant cutoff) {
		return jdbc.sql("SELECT availability.lookup_sample_drop_before(?)")
			.param(Timestamp.from(cutoff))
			.query(Integer.class)
			.single();
	}

}
