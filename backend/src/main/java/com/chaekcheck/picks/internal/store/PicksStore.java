package com.chaekcheck.picks.internal.store;

import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

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
 * <p>
 * Since cycle 21 (ADR-0122) the librarian picks are the {@code librarian} rows of {@code list_book}, their fields the
 * {@code librarian} rows of {@code list_field}. The fetch reads and writes those rows only; the other lists come with
 * the import. Both writers take the list's advisory lock ({@link #lockList}). The cover clean-up keeps every cover any
 * list uses.
 */
@Component
public class PicksStore {

	/** Name of a new field without a name from the API [확정] (C14 R2-5). */
	public static final String OTHER_NAME = "기타";

	static final String LIBRARIAN = "librarian";

	/** The librarian rows as {@link PickRow} columns; {@code drCodeName} is the field's name in {@code list_field}. */
	private static final String PICK_COLUMNS = """
			b.source_no AS recom_no, b.field_code AS dr_code, f.name AS dr_code_name,
			  b.recommended_month AS recom_month, b.registered_on, b.title, b.author, b.publisher, b.isbn13,
			  b.cover_source
			FROM picks.list_book b JOIN picks.list_field f ON f.list_key = b.list_key AND f.code = b.field_code
			WHERE b.list_key = 'librarian'""";

	/**
	 * Book order inside a field, as the cache shows it (PicksCache.BOOK_ORDER): month, registration date (unknown
	 * last), then {@code recomNo} as text, each latest first. Stored as {@code list_book.position}.
	 */
	static final Comparator<PickRow> BOOK_ORDER = Comparator
		.comparing(PickRow::recomMonth, Comparator.reverseOrder())
		.thenComparing(PickRow::registeredOn, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(PickRow::recomNo, Comparator.reverseOrder());

	private final JdbcClient jdbc;

	PicksStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Whether a librarian list is stored (the other lists do not count). */
	public boolean hasPicks() {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM picks.list_book WHERE list_key = 'librarian')")
			.query(Boolean.class)
			.single();
	}

	/**
	 * Waits for the other writer of the same list until this transaction ends (design-delta "데이터" 잠금 [안]): the
	 * fetch and the import's {@code replace} never write the same list at once.
	 */
	public void lockList(String listKey) {
		jdbc.sql("SELECT pg_advisory_xact_lock(hashtext('picks.list_book'), hashtext(:list))")
			.param("list", listKey)
			.query()
			.listOfRows();
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

	/** The stored librarian list, in no particular order; {@code drCodeName} is the field's stored name. */
	public List<PickRow> picks() {
		return jdbc.sql("SELECT " + PICK_COLUMNS).query((rs, rowNum) -> pick(rs)).list();
	}

	/**
	 * One transaction: when {@code kept} holds exactly the stored rows (every value equal but the field name [안]) a
	 * SUCCEEDED run with {@code changed=false} only; otherwise the stored librarian rows replaced and a SUCCEEDED run
	 * with {@code changed=true}. Fields new to {@code list_field} go after the others in {@link KeptPicks#CODE_ORDER},
	 * named by the API or "기타" (C14 R2-5); a field already there keeps its name and place. The field name is left
	 * out of the comparison: a known field is stored with our short name, not the API's.
	 * @param requestCount requests so far (the list); the covers are added later
	 */
	@Transactional
	public Saved saveSucceeded(KeptPicks kept, String trigger, Instant startedAt, Instant finishedAt, int requestCount) {
		lockList(LIBRARIAN);
		boolean changed = !withoutNames(picks()).equals(withoutNames(kept.rows()));
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
			jdbc.sql("DELETE FROM picks.list_book WHERE list_key = 'librarian'").update();
			addNewFields(kept.rows());
			List<PickRow> ordered = new ArrayList<>(kept.rows());
			ordered.sort(BOOK_ORDER);
			Map<String, Integer> positions = new HashMap<>();
			for (PickRow row : ordered) {
				jdbc.sql("""
						INSERT INTO picks.list_book (list_key, field_code, position, title, author, publisher, isbn13,
						  recommended_month, source_no, registered_on, cover_source)
						VALUES ('librarian', :drCode, :position, :title, :author, :publisher, :isbn13, :recomMonth,
						  :recomNo, :registeredOn, :coverSource)""")
					.param("drCode", row.drCode())
					.param("position", positions.merge(row.drCode(), 1, Integer::sum))
					.param("title", row.title())
					.param("author", row.author())
					.param("publisher", row.publisher())
					.param("isbn13", row.isbn13())
					.param("recomMonth", row.recomMonth())
					.param("recomNo", row.recomNo())
					.param("registeredOn", row.registeredOn())
					.param("coverSource", row.coverSource())
					.update();
			}
		}
		return new Saved(runId, changed);
	}

	private void addNewFields(List<PickRow> rows) {
		List<String> stored = jdbc.sql("SELECT code FROM picks.list_field WHERE list_key = 'librarian'")
			.query(String.class)
			.list();
		Map<String, String> names = new TreeMap<>(KeptPicks.CODE_ORDER);
		for (PickRow row : rows) {
			if (!stored.contains(row.drCode())) {
				String name = row.drCodeName() == null || row.drCodeName().isBlank() ? OTHER_NAME : row.drCodeName();
				names.merge(row.drCode(), name, (first, next) -> first);
			}
		}
		names.forEach((code, name) -> jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position)
				SELECT 'librarian', :code, :name, coalesce(max(position), 0) + 1
				FROM picks.list_field WHERE list_key = 'librarian'""")
			.param("code", code)
			.param("name", name)
			.update());
	}

	private static Set<PickRow> withoutNames(List<PickRow> rows) {
		Set<PickRow> set = new HashSet<>();
		for (PickRow row : rows) {
			set.add(new PickRow(row.recomNo(), row.drCode(), null, row.recomMonth(), row.registeredOn(), row.title(),
					row.author(), row.publisher(), row.isbn13(), row.coverSource()));
		}
		return set;
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

	/**
	 * Cover addresses of the stored librarian list with no stored cover yet, in display order of their books. The
	 * other lists' covers come with the import (ADR-0123), never from here.
	 */
	public List<String> missingCoverSources() {
		return jdbc.sql("""
				SELECT b.cover_source FROM picks.list_book b
				WHERE b.list_key = 'librarian' AND b.cover_source IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM picks.cover c WHERE c.source_url = b.cover_source)
				GROUP BY b.cover_source
				ORDER BY max(b.recommended_month) DESC, max(b.registered_on) DESC NULLS LAST,
				  max(b.source_no COLLATE "C") DESC""")
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
	 * Deletes the covers the librarian list does not use (ADR-0091). The other lists' covers are files served by the
	 * web server since 2026-10-20 (CoverFiles), so their rows go too.
	 * @return covers deleted
	 */
	public int deleteUnusedCovers() {
		return jdbc.sql("""
				DELETE FROM picks.cover c
				WHERE NOT EXISTS (SELECT 1 FROM picks.list_book b
				  WHERE b.list_key = 'librarian' AND b.cover_source = c.source_url)""").update();
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

	/** Every list's fields, by list and then display order. */
	public List<StoredField> fields() {
		return jdbc.sql("SELECT list_key, code, name FROM picks.list_field ORDER BY list_key, position")
			.query((rs, rowNum) -> new StoredField(rs.getString(1), rs.getString(2), rs.getString(3)))
			.list();
	}

	/** One list's stored rows with each book's cover address and stored cover id, in no particular order. */
	public List<StoredBook> books(String listKey) {
		return jdbc.sql("""
				SELECT b.id, b.field_code, b.position, b.title, b.author, b.isbn13, b.recommended_month, b.year_book,
				  b.video_id, b.toc_no, b.source_no, b.registered_on, b.book_id, b.cover_source,
				  c.id AS cover_id
				FROM picks.list_book b LEFT JOIN picks.cover c ON c.source_url = b.cover_source
				WHERE b.list_key = :list""")
			.param("list", listKey)
			.query((rs, rowNum) -> new StoredBook(rs.getLong("id"), rs.getString("field_code"), rs.getInt("position"),
					rs.getString("title"), rs.getString("author"), rs.getString("isbn13"),
					date(rs, "recommended_month"), rs.getObject("year_book", Integer.class), rs.getString("video_id"),
					rs.getObject("toc_no", Integer.class), rs.getString("source_no"), date(rs, "registered_on"),
					rs.getObject("book_id", Long.class), rs.getString("cover_source"), rs.getObject("cover_id", Long.class)))
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

	public record StoredCover(String contentType, byte[] image) {
	}

	public record StoredField(String listKey, String code, String name) {
	}

	/**
	 * One {@code list_book} row (design-delta "데이터").
	 * @param bookId the matched book of a Lee Dong-jin or SNU row; null for the librarian rows (matched by the cache)
	 * @param coverSource the cover's source address, or null
	 * @param coverId the stored cover (the librarian list's), or null
	 */
	public record StoredBook(long id, String fieldCode, int position, String title, String author, String isbn13,
			LocalDate recommendedMonth, Integer yearBook, String videoId, Integer tocNo, String sourceNo,
			LocalDate registeredOn, Long bookId, String coverSource, Long coverId) {
	}

}
