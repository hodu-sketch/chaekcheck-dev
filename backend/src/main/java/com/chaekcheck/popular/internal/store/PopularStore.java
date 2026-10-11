package com.chaekcheck.popular.internal.store;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.popular.internal.fetch.CategoryRanking;
import com.chaekcheck.popular.internal.fetch.FetchFailure;
import com.chaekcheck.popular.internal.fetch.MonthRanking;
import com.chaekcheck.popular.internal.fetch.RankedBook;

/**
 * The {@code popular} schema (plan cycle 10 design-delta "데이터"), with {@code JdbcClient}. A successful fetch writes
 * its SUCCEEDED run and the month's ranking in one transaction; only the cover counts are updated later (design-delta
 * "받기 기록 쓰는 순서", user decision 2026-10-12). Ranking rows are never deleted but by a new fetch of the same
 * month (spec R1-5).
 */
@Component
public class PopularStore {

	private final JdbcClient jdbc;

	PopularStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The latest stored month, if any. */
	public Optional<YearMonth> latestMonth() {
		return jdbc.sql("SELECT target_month FROM popular.ranking_month ORDER BY target_month DESC LIMIT 1")
			.query((rs, rowNum) -> YearMonth.from(rs.getDate(1).toLocalDate()))
			.optional();
	}

	/** When {@code month} was stored, if it is. */
	public Optional<Instant> fetchedAt(YearMonth month) {
		return jdbc.sql("SELECT fetched_at FROM popular.ranking_month WHERE target_month = :month")
			.param("month", day(month))
			.query((rs, rowNum) -> rs.getTimestamp(1).toInstant())
			.optional();
	}

	/** FAILED runs for {@code month} started at or after {@code since}. */
	public int failuresSince(YearMonth month, Instant since) {
		return jdbc.sql("""
				SELECT count(*) FROM popular.fetch_run
				WHERE target_month = :month AND started_at >= :since AND status = 'FAILED'""")
			.param("month", day(month))
			.param("since", Timestamp.from(since))
			.query(Integer.class)
			.single();
	}

	/**
	 * One transaction: the SUCCEEDED run ({@code finished_at} = the time the ranking is stored, cover counts 0), the
	 * month's old ranking deleted (CASCADE), then the month, its 15 categories and up to 750 books.
	 * @return the run id
	 */
	@Transactional
	public long saveSucceeded(MonthRanking ranking, String trigger, Instant startedAt, Instant storedAt) {
		LocalDate month = day(ranking.month());
		long runId = jdbc.sql("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, request_count,
				  book_count, missing_isbn_count)
				VALUES (:month, :trigger, :started, :finished, 'SUCCEEDED', :requests, :books, :missing) RETURNING id""")
			.param("month", month)
			.param("trigger", trigger)
			.param("started", Timestamp.from(startedAt))
			.param("finished", Timestamp.from(storedAt))
			.param("requests", ranking.requestCount())
			.param("books", ranking.bookCount())
			.param("missing", ranking.missingIsbnCount())
			.query(Long.class)
			.single();
		jdbc.sql("DELETE FROM popular.ranking_month WHERE target_month = :month").param("month", month).update();
		jdbc.sql("INSERT INTO popular.ranking_month (target_month, fetch_run_id, fetched_at) VALUES (:month, :run, :at)")
			.param("month", month)
			.param("run", runId)
			.param("at", Timestamp.from(storedAt))
			.update();
		List<String> codes = new ArrayList<>();
		List<Integer> ranks = new ArrayList<>();
		List<String> isbns = new ArrayList<>();
		List<String> titles = new ArrayList<>();
		List<String> themas = new ArrayList<>();
		List<String> covers = new ArrayList<>();
		for (CategoryRanking category : ranking.categories()) {
			jdbc.sql("""
					INSERT INTO popular.ranking_category (target_month, category_code, sales_copies, display_order)
					VALUES (:month, :code, :sales, :order)""")
				.param("month", month)
				.param("code", category.category().code())
				.param("sales", category.salesCopies())
				.param("order", category.displayOrder())
				.update();
			for (RankedBook book : category.books()) {
				codes.add(category.category().code());
				ranks.add(book.rank());
				isbns.add(book.isbn13());
				titles.add(book.title());
				themas.add(book.thema());
				covers.add(book.coverPath());
			}
		}
		jdbc.sql("""
				INSERT INTO popular.ranking_entry (target_month, category_code, rank, isbn13, title, thema, cover_path)
				SELECT :month, e.code, e.rank, e.isbn, e.title, e.thema, e.cover
				FROM unnest(CAST(:codes AS text[]), CAST(:ranks AS smallint[]), CAST(:isbns AS text[]),
				  CAST(:titles AS text[]), CAST(:themas AS text[]), CAST(:covers AS text[]))
				  AS e(code, rank, isbn, title, thema, cover)""")
			.param("month", month)
			.param("codes", codes.toArray(String[]::new))
			.param("ranks", ranks.toArray(Integer[]::new))
			.param("isbns", isbns.toArray(String[]::new))
			.param("titles", titles.toArray(String[]::new))
			.param("themas", themas.toArray(String[]::new))
			.param("covers", covers.toArray(String[]::new))
			.update();
		return runId;
	}

	/** A FAILED run: no ranking is stored, the month shown stays as it was. */
	public void saveFailed(YearMonth month, String trigger, Instant startedAt, Instant finishedAt, FetchFailure failure,
			int requestCount) {
		jdbc.sql("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, failure_reason,
				  failure_detail, request_count)
				VALUES (:month, :trigger, :started, :finished, 'FAILED', :reason, :detail, :requests)""")
			.param("month", day(month))
			.param("trigger", trigger)
			.param("started", Timestamp.from(startedAt))
			.param("finished", Timestamp.from(finishedAt))
			.param("reason", failure.reason().value())
			.param("detail", failure.getMessage())
			.param("requests", requestCount)
			.update();
	}

	/** Cover paths of {@code month} with no stored cover yet, in display then rank order. */
	public List<String> missingCoverPaths(YearMonth month) {
		return jdbc.sql("""
				SELECT e.cover_path FROM popular.ranking_entry e
				JOIN popular.ranking_category c USING (target_month, category_code)
				WHERE e.target_month = :month AND e.cover_path IS NOT NULL
				  AND NOT EXISTS (SELECT 1 FROM popular.cover v WHERE v.source_path = e.cover_path)
				GROUP BY e.cover_path
				ORDER BY min(c.display_order * 100 + e.rank)""")
			.param("month", day(month))
			.query(String.class)
			.list();
	}

	/** Stores one cover; a path stored meanwhile is left as it is. */
	public void saveCover(String path, String contentType, byte[] image, Instant fetchedAt) {
		jdbc.sql("""
				INSERT INTO popular.cover (source_path, content_type, image, byte_size, fetched_at)
				VALUES (:path, :type, :image, :size, :at) ON CONFLICT (source_path) DO NOTHING""")
			.param("path", path)
			.param("type", contentType)
			.param("image", image)
			.param("size", image.length)
			.param("at", Timestamp.from(fetchedAt))
			.update();
	}

	/**
	 * Deletes the covers no ranking of the latest two stored months uses (plan design follow-up #2, ADR-0075).
	 * @return covers deleted
	 */
	public int deleteUnusedCovers() {
		return jdbc.sql("""
				DELETE FROM popular.cover v
				WHERE NOT EXISTS (
				  SELECT 1 FROM popular.ranking_entry e
				  WHERE e.cover_path = v.source_path AND e.target_month IN (
				    SELECT target_month FROM popular.ranking_month ORDER BY target_month DESC LIMIT 2))""")
			.update();
	}

	/** The only change to a run after it is written. */
	public void updateCoverCounts(long runId, int fetched, int failed) {
		jdbc.sql("UPDATE popular.fetch_run SET cover_fetched_count = :fetched, cover_failed_count = :failed WHERE id = :id")
			.param("fetched", fetched)
			.param("failed", failed)
			.param("id", runId)
			.update();
	}

	/**
	 * The latest stored month as the cache holds it: categories in display order, books in rank order, with the id of
	 * the stored cover of each book (if any).
	 */
	public Optional<StoredMonth> readLatestMonth() {
		Optional<StoredMonthHead> head = jdbc.sql("""
				SELECT target_month, fetched_at FROM popular.ranking_month ORDER BY target_month DESC LIMIT 1""")
			.query((rs, rowNum) -> new StoredMonthHead(YearMonth.from(rs.getDate(1).toLocalDate()),
					rs.getTimestamp(2).toInstant()))
			.optional();
		if (head.isEmpty()) {
			return Optional.empty();
		}
		Map<String, List<StoredBook>> byCategory = new LinkedHashMap<>();
		jdbc.sql("""
				SELECT c.category_code, e.rank, e.isbn13, e.title, v.id AS cover_id
				FROM popular.ranking_category c
				JOIN popular.ranking_entry e USING (target_month, category_code)
				LEFT JOIN popular.cover v ON v.source_path = e.cover_path
				WHERE c.target_month = :month
				ORDER BY c.display_order, e.rank""")
			.param("month", day(head.get().month()))
			.query((rs, rowNum) -> Map.entry(rs.getString("category_code"), new StoredBook(rs.getInt("rank"),
					rs.getString("title"), rs.getString("isbn13"), rs.getObject("cover_id", Long.class))))
			.list()
			.forEach(row -> byCategory.computeIfAbsent(row.getKey(), code -> new ArrayList<>()).add(row.getValue()));
		List<StoredCategory> categories = byCategory.entrySet()
			.stream()
			.map(entry -> new StoredCategory(entry.getKey(), List.copyOf(entry.getValue())))
			.toList();
		return Optional.of(new StoredMonth(head.get().month(), head.get().fetchedAt(), categories));
	}

	/** One stored cover. */
	public Optional<StoredCover> cover(long id) {
		return jdbc.sql("SELECT content_type, image FROM popular.cover WHERE id = :id")
			.param("id", id)
			.query((rs, rowNum) -> new StoredCover(rs.getString(1), rs.getBytes(2)))
			.optional();
	}

	private static LocalDate day(YearMonth month) {
		return month.atDay(1);
	}

	private record StoredMonthHead(YearMonth month, Instant fetchedAt) {
	}

	/** The latest month: categories in display order. */
	public record StoredMonth(YearMonth month, Instant fetchedAt, List<StoredCategory> categories) {
	}

	/** @param code {@code ALL} or the site id */
	public record StoredCategory(String code, List<StoredBook> books) {
	}

	/** @param coverId the stored cover, or null */
	public record StoredBook(int rank, String title, String isbn13, Long coverId) {
	}

	public record StoredCover(String contentType, byte[] image) {
	}

}
