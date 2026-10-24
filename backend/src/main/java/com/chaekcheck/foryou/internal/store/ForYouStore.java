package com.chaekcheck.foryou.internal.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.foryou.internal.build.BaseOrder.Entry;
import com.chaekcheck.foryou.internal.build.MatchedRow;

/**
 * The {@code foryou} build tables with {@code JdbcClient} (cycle 23 design-delta "데이터"). A succeeded build writes its
 * rows, scores and base list in one transaction with the status; a failed build only changes its status and note.
 */
@Component
public class ForYouStore {

	private final JdbcClient jdbc;

	public ForYouStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** A build left running by a crash or a stop is failed at start (user approval 2026-10-24). */
	public int failRunning(Instant at) {
		return jdbc.sql("""
				UPDATE foryou.build_run SET status = 'failed', finished_at = :at,
				  note = concat_ws(E'\\n', note, 'left running, failed at start')
				WHERE status = 'running'""")
			.param("at", Timestamp.from(at))
			.update();
	}

	public boolean succeededOn(LocalDate day) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM foryou.build_run WHERE status = 'succeeded' AND built_on = ?)")
			.param(day)
			.query(Boolean.class)
			.single();
	}

	public long start(LocalDate builtOn, Instant at) {
		return jdbc.sql("INSERT INTO foryou.build_run (built_on, started_at, status) VALUES (?, ?, 'running') RETURNING id")
			.params(builtOn, Timestamp.from(at))
			.query(Long.class)
			.single();
	}

	@Transactional
	public void succeed(long runId, List<MatchedRow> rows, Map<Long, Double> scores, List<Entry> entries, String note,
			Instant at) {
		for (MatchedRow row : rows) {
			jdbc.sql("""
					INSERT INTO foryou.popular_row (run_id, source, site_library_id, period, rank, loans_now, reservations,
					  title, author, publisher, isbn13, book_id)
					VALUES (:run, :source, :site, :period, :rank, :loans, :reservations, :title, :author, :publisher, :isbn,
					  :book)""")
				.param("run", runId)
				.param("source", row.source())
				.param("site", row.siteLibraryId())
				.param("period", row.period())
				.param("rank", Math.min(row.row().rank(), Short.MAX_VALUE))
				.param("loans", row.row().loansNow())
				.param("reservations", row.row().reservations())
				.param("title", row.row().title())
				.param("author", row.row().author())
				.param("publisher", row.row().publisher())
				.param("isbn", row.row().isbn13())
				.param("book", row.bookId())
				.update();
		}
		scores.forEach((bookId, score) -> jdbc.sql("INSERT INTO foryou.book_score (run_id, book_id, score) VALUES (?, ?, ?)")
			.params(runId, bookId, score.floatValue())
			.update());
		for (int i = 0; i < entries.size(); i++) {
			jdbc.sql("INSERT INTO foryou.base_entry (run_id, position, book_id, from_popular) VALUES (?, ?, ?, ?)")
				.params(runId, i + 1, entries.get(i).bookId(), entries.get(i).fromPopular())
				.update();
		}
		finish(runId, "succeeded", note, at);
	}

	public void fail(long runId, String note, Instant at) {
		finish(runId, "failed", note, at);
	}

	private void finish(long runId, String status, String note, Instant at) {
		jdbc.sql("UPDATE foryou.build_run SET status = ?, note = ?, finished_at = ? WHERE id = ? AND status = 'running'")
			.params(status, note, Timestamp.from(at), runId)
			.update();
	}

	/** The newest succeeded build, its base list in order and its scores. */
	public Optional<LastBuild> lastSucceeded() {
		Optional<Run> run = jdbc.sql("""
				SELECT id, built_on FROM foryou.build_run WHERE status = 'succeeded'
				ORDER BY built_on DESC, id DESC LIMIT 1""")
			.query((rs, rowNum) -> new Run(rs.getLong("id"), rs.getObject("built_on", LocalDate.class)))
			.optional();
		if (run.isEmpty()) {
			return Optional.empty();
		}
		long runId = run.get().id();
		List<Entry> entries = new ArrayList<>();
		jdbc.sql("SELECT book_id, from_popular FROM foryou.base_entry WHERE run_id = ? ORDER BY position")
			.param(runId)
			.query(rs -> {
				entries.add(new Entry(rs.getLong("book_id"), rs.getBoolean("from_popular")));
			});
		Map<Long, Double> scores = new HashMap<>();
		jdbc.sql("SELECT book_id, score FROM foryou.book_score WHERE run_id = ?").param(runId).query(rs -> {
			scores.put(rs.getLong("book_id"), (double) rs.getFloat("score"));
		});
		return Optional.of(new LastBuild(runId, run.get().builtOn(), entries, scores));
	}

	/**
	 * Deletes the runs built before {@code before} with their rows (cascade), but never the newest succeeded run
	 * (user approval 2026-10-24).
	 */
	public int deleteBefore(LocalDate before) {
		return jdbc.sql("""
				DELETE FROM foryou.build_run WHERE built_on < :before AND id IS DISTINCT FROM (
				  SELECT id FROM foryou.build_run WHERE status = 'succeeded' ORDER BY built_on DESC, id DESC LIMIT 1)""")
			.param("before", before)
			.update();
	}

	private record Run(long id, LocalDate builtOn) {
	}

	/** @param scores score of each scored book (representative editions) */
	public record LastBuild(long runId, LocalDate builtOn, List<Entry> entries, Map<Long, Double> scores) {

		public LastBuild {
			entries = List.copyOf(entries);
			scores = Map.copyOf(scores);
		}

	}

}
