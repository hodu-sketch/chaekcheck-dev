package com.chaekcheck.picks.internal.store;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.internal.fetch.FetchFailure;
import com.chaekcheck.picks.internal.fetch.KeptPicks;
import com.chaekcheck.picks.internal.fetch.PickRow;

/**
 * The {@code picks} schema (plan cycle 14 design-delta "데이터"), with {@code JdbcClient}. A successful fetch writes its
 * SUCCEEDED run and, when the list changed, the new list in one transaction; only the request and cover counts are
 * updated later. A failed fetch writes one FAILED run and leaves the list as it is (spec R1-3).
 */
@Component
public class PicksStore {

	private static final String PICK_COLUMNS = """
			recom_no, dr_code, dr_code_name, recom_month, registered_on, title, author, publisher, isbn13, cover_source""";

	private final JdbcClient jdbc;

	PicksStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Whether a list is stored. */
	public boolean hasPicks() {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM picks.pick)").query(Boolean.class).single();
	}

	/** Status of the latest run (by start), if any. */
	public Optional<String> lastStatus() {
		return jdbc.sql("SELECT status FROM picks.fetch_run ORDER BY started_at DESC, id DESC LIMIT 1")
			.query(String.class)
			.optional();
	}

	/** {@code finished_at} of the latest SUCCEEDED run: when the list shown was last fetched. */
	public Optional<Instant> lastSucceededAt() {
		return jdbc.sql("""
				SELECT finished_at FROM picks.fetch_run WHERE status = 'SUCCEEDED'
				ORDER BY started_at DESC, id DESC LIMIT 1""")
			.query((rs, rowNum) -> rs.getTimestamp(1).toInstant())
			.optional();
	}

	/** The stored list, in no particular order. */
	public List<PickRow> picks() {
		return jdbc.sql("SELECT " + PICK_COLUMNS + " FROM picks.pick").query((rs, rowNum) -> pick(rs)).list();
	}

	/**
	 * One transaction: when {@code kept} holds exactly the stored rows (every value equal [안]) a SUCCEEDED run with
	 * {@code changed=false} only; otherwise the stored list replaced and a SUCCEEDED run with {@code changed=true}.
	 * @param requestCount requests so far (the list); the covers are added later
	 */
	@Transactional
	public Saved saveSucceeded(KeptPicks kept, String trigger, Instant startedAt, Instant finishedAt, int requestCount) {
		boolean changed = !new HashSet<>(picks()).equals(new HashSet<>(kept.rows()));
		long runId = jdbc.sql("""
				INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, changed, request_count,
				  received_count, kept_count, missing_isbn_count, new_category_codes)
				VALUES (:trigger, :started, :finished, 'SUCCEEDED', :changed, :requests, :received, :kept, :missing,
				  :codes) RETURNING id""")
			.param("trigger", trigger)
			.param("started", Timestamp.from(startedAt))
			.param("finished", Timestamp.from(finishedAt))
			.param("changed", changed)
			.param("requests", requestCount)
			.param("received", kept.receivedCount())
			.param("kept", kept.rows().size())
			.param("missing", kept.missingIsbnCount())
			.param("codes", kept.newCategoryCodes())
			.query(Long.class)
			.single();
		if (changed) {
			jdbc.sql("DELETE FROM picks.pick").update();
			for (PickRow row : kept.rows()) {
				jdbc.sql("INSERT INTO picks.pick (" + PICK_COLUMNS + """
						) VALUES (:recomNo, :drCode, :drCodeName, :recomMonth, :registeredOn, :title, :author,
						  :publisher, :isbn13, :coverSource)""")
					.param("recomNo", row.recomNo())
					.param("drCode", row.drCode())
					.param("drCodeName", row.drCodeName())
					.param("recomMonth", row.recomMonth())
					.param("registeredOn", row.registeredOn())
					.param("title", row.title())
					.param("author", row.author())
					.param("publisher", row.publisher())
					.param("isbn13", row.isbn13())
					.param("coverSource", row.coverSource())
					.update();
			}
		}
		return new Saved(runId, changed);
	}

	/** A FAILED run: the list stays as it was. */
	public void saveFailed(String trigger, Instant startedAt, Instant finishedAt, FetchFailure failure, int requestCount,
			int receivedCount) {
		jdbc.sql("""
				INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, failure_reason, failure_detail,
				  request_count, received_count)
				VALUES (:trigger, :started, :finished, 'FAILED', :reason, :detail, :requests, :received)""")
			.param("trigger", trigger)
			.param("started", Timestamp.from(startedAt))
			.param("finished", Timestamp.from(finishedAt))
			.param("reason", failure.reason().value())
			.param("detail", failure.getMessage())
			.param("requests", requestCount)
			.param("received", receivedCount)
			.update();
	}

	/** Cover addresses of the stored list with no stored cover yet, in display order of their books. */
	public List<String> missingCoverSources() {
		return jdbc.sql("""
				SELECT p.cover_source FROM picks.pick p
				WHERE p.cover_source IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM picks.cover c WHERE c.source_url = p.cover_source)
				GROUP BY p.cover_source
				ORDER BY max(p.recom_month) DESC, max(p.registered_on) DESC NULLS LAST, max(p.recom_no) DESC""")
			.query(String.class)
			.list();
	}

	/** Stores one cover; an address stored meanwhile is left as it is. */
	public void saveCover(String sourceUrl, String contentType, byte[] image, Instant fetchedAt) {
		jdbc.sql("""
				INSERT INTO picks.cover (source_url, content_type, image, byte_size, fetched_at)
				VALUES (:url, :type, :image, :size, :at) ON CONFLICT (source_url) DO NOTHING""")
			.param("url", sourceUrl)
			.param("type", contentType)
			.param("image", image)
			.param("size", image.length)
			.param("at", Timestamp.from(fetchedAt))
			.update();
	}

	/**
	 * Deletes the covers the stored list does not use (ADR-0091).
	 * @return covers deleted
	 */
	public int deleteUnusedCovers() {
		return jdbc.sql("""
				DELETE FROM picks.cover c
				WHERE NOT EXISTS (SELECT 1 FROM picks.pick p WHERE p.cover_source = c.source_url)""").update();
	}

	/** The only change to a run after it is written: the covers' counts and the requests they took. */
	public void updateCoverCounts(long runId, int fetched, int failed, int requestCount) {
		jdbc.sql("""
				UPDATE picks.fetch_run SET cover_fetched_count = :fetched, cover_failed_count = :failed,
				  request_count = :requests
				WHERE id = :id""")
			.param("fetched", fetched)
			.param("failed", failed)
			.param("requests", requestCount)
			.param("id", runId)
			.update();
	}

	/** The stored list as the cache holds it, with each book's stored cover id. */
	public List<StoredPick> readPicks() {
		return jdbc.sql("""
				SELECT p.recom_no, p.dr_code, p.dr_code_name, p.recom_month, p.registered_on, p.title, p.author,
				  p.isbn13, c.id AS cover_id
				FROM picks.pick p LEFT JOIN picks.cover c ON c.source_url = p.cover_source""")
			.query((rs, rowNum) -> new StoredPick(rs.getString("recom_no"), rs.getString("dr_code"),
					rs.getString("dr_code_name"), rs.getDate("recom_month").toLocalDate(), date(rs, "registered_on"),
					rs.getString("title"), rs.getString("author"), rs.getString("isbn13"),
					rs.getObject("cover_id", Long.class)))
			.list();
	}

	/** One stored cover. */
	public Optional<StoredCover> cover(long id) {
		return jdbc.sql("SELECT content_type, image FROM picks.cover WHERE id = :id")
			.param("id", id)
			.query((rs, rowNum) -> new StoredCover(rs.getString(1), rs.getBytes(2)))
			.optional();
	}

	private static PickRow pick(ResultSet rs) throws SQLException {
		return new PickRow(rs.getString("recom_no"), rs.getString("dr_code"), rs.getString("dr_code_name"),
				rs.getDate("recom_month").toLocalDate(), date(rs, "registered_on"), rs.getString("title"),
				rs.getString("author"), rs.getString("publisher"), rs.getString("isbn13"), rs.getString("cover_source"));
	}

	private static LocalDate date(ResultSet rs, String column) throws SQLException {
		Date date = rs.getDate(column);
		return date == null ? null : date.toLocalDate();
	}

	/** @param changed whether the stored list was replaced */
	public record Saved(long runId, boolean changed) {
	}

	/** @param coverId the stored cover, or null */
	public record StoredPick(String recomNo, String drCode, String drCodeName, LocalDate recomMonth,
			LocalDate registeredOn, String title, String author, String isbn13, Long coverId) {
	}

	public record StoredCover(String contentType, byte[] image) {
	}

}
