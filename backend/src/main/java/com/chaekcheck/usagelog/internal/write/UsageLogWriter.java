package com.chaekcheck.usagelog.internal.write;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer.ResponseKey;
import com.chaekcheck.usagelog.internal.buffer.UsageBuffer.ResponseTotals;
import com.chaekcheck.usagelog.internal.buffer.UsageRow;

/**
 * Writes one batch in one transaction with asynchronous commit (ADR-0097): {@code SET LOCAL synchronous_commit TO
 * OFF}, one {@code batchUpdate} per table, the library answers as an upsert that adds to the day's line. Only this
 * transaction commits asynchronously; a crash may lose its last moments, which a usage log can afford. PostgreSQL
 * text refuses the NUL character, so it is taken out of every text value; one such value would fail the whole batch.
 */
public class UsageLogWriter {

	static final String INSERT_SEARCH = """
			INSERT INTO usagelog.search_event (received_at, kind, title, author, publisher, exact, roles, sort, filter,
			  scope, visit, residence_region_ids, work_region_ids, library_ids, result_count, login_status, client_ip)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet)""";

	static final String INSERT_BOOK_VIEW = """
			INSERT INTO usagelog.book_view (received_at, book_id, residence_region_ids, work_region_ids, library_ids,
			  login_status, client_ip, account_id, visit_key)
			VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?)""";

	static final String INSERT_MORE = """
			INSERT INTO usagelog.search_more (received_at, kind, seq, library_id, title, author, publisher, exact, roles,
			  sort, filter, scope, visit, residence_region_ids, work_region_ids, library_ids, login_status, client_ip)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet)""";

	static final String INSERT_CLICK = """
			INSERT INTO usagelog.library_click (received_at, library_id, place, screen, book_id, login_status, client_ip,
			  account_id, visit_key)
			VALUES (?, ?, ?, ?, ?, ?, ?::inet, ?, ?)""";

	static final String INSERT_SCREEN = """
			INSERT INTO usagelog.screen_event (received_at, kind, picks_list, picks_category, book_id,
			  residence_region_ids, work_region_ids, library_id, login_status, client_ip, account_id, visit_key)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?::inet, ?, ?)""";

	static final String INSERT_REJECTED = """
			INSERT INTO usagelog.rejected_event (received_at, client_ip, reason, raw_values)
			VALUES (?, ?::inet, ?, ?::jsonb)""";

	static final String INSERT_AI_SEARCH = """
			INSERT INTO usagelog.ai_search_event (received_at, elapsed_ms, turn_no, keyword_result, title_like,
			  pick_result, candidate_count, picked_book_ids, input_tokens, output_tokens)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""";

	static final String INSERT_DROPPED = """
			INSERT INTO usagelog.dropped_batch (dropped_at, row_count, reason) VALUES (?, ?, ?)""";

	static final String UPSERT_RESPONSE = """
			INSERT INTO usagelog.library_response_daily AS d (day, library_id, source, ok_count, failed_count,
			  timeout_count, skipped_count, ok_ms_total, ok_ms_max, cached_count, body_too_large_count)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
			ON CONFLICT (day, library_id, source) DO UPDATE SET
			  ok_count = d.ok_count + EXCLUDED.ok_count,
			  failed_count = d.failed_count + EXCLUDED.failed_count,
			  timeout_count = d.timeout_count + EXCLUDED.timeout_count,
			  skipped_count = d.skipped_count + EXCLUDED.skipped_count,
			  ok_ms_total = d.ok_ms_total + EXCLUDED.ok_ms_total,
			  ok_ms_max = GREATEST(d.ok_ms_max, EXCLUDED.ok_ms_max),
			  cached_count = d.cached_count + EXCLUDED.cached_count,
			  body_too_large_count = d.body_too_large_count + EXCLUDED.body_too_large_count""";

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaction;

	public UsageLogWriter(JdbcTemplate jdbc, TransactionTemplate transaction) {
		this.jdbc = jdbc;
		this.transaction = transaction;
	}

	/** One given-up batch for {@code dropped_batch}. */
	public record Dropped(Instant droppedAt, long rowCount, String reason) {
	}

	/** Writes everything or nothing; throws on a database failure. */
	public void write(List<UsageRow> rows, Map<ResponseKey, ResponseTotals> responses, List<Dropped> dropped) {
		List<UsageRow.Search> searches = new ArrayList<>();
		List<UsageRow.BookView> views = new ArrayList<>();
		List<UsageRow.More> mores = new ArrayList<>();
		List<UsageRow.LibraryClick> clicks = new ArrayList<>();
		List<UsageRow.ScreenEvent> screens = new ArrayList<>();
		List<UsageRow.Rejected> rejected = new ArrayList<>();
		List<UsageRow.AiSearch> aiSearches = new ArrayList<>();
		for (UsageRow row : rows) {
			switch (row) {
				case UsageRow.Search r -> searches.add(r);
				case UsageRow.BookView r -> views.add(r);
				case UsageRow.More r -> mores.add(r);
				case UsageRow.LibraryClick r -> clicks.add(r);
				case UsageRow.ScreenEvent r -> screens.add(r);
				case UsageRow.Rejected r -> rejected.add(r);
				case UsageRow.AiSearch r -> aiSearches.add(r);
			}
		}
		List<Map.Entry<ResponseKey, ResponseTotals>> answers = new ArrayList<>(responses.entrySet());
		transaction.executeWithoutResult(status -> {
			jdbc.execute("SET LOCAL synchronous_commit TO OFF");
			batch(INSERT_SEARCH, searches, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setString(i++, text(r.kind()));
				i = conditions(ps, i, r.conditions());
				setInteger(ps, i++, r.resultCount());
				ps.setString(i++, text(r.loginStatus()));
				ps.setString(i, text(r.clientIp()));
			});
			batch(INSERT_BOOK_VIEW, views, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setLong(i++, r.bookId());
				ps.setArray(i++, bigints(ps, r.residenceRegionIds()));
				ps.setArray(i++, bigints(ps, r.workRegionIds()));
				ps.setArray(i++, bigints(ps, r.libraryIds()));
				ps.setString(i++, text(r.loginStatus()));
				ps.setString(i++, text(r.clientIp()));
				setLong(ps, i++, r.accountId());
				ps.setString(i, text(r.visitKey()));
			});
			batch(INSERT_MORE, mores, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setString(i++, text(r.kind()));
				ps.setInt(i++, r.seq());
				setLong(ps, i++, r.libraryId());
				i = conditions(ps, i, r.conditions());
				ps.setString(i++, text(r.loginStatus()));
				ps.setString(i, text(r.clientIp()));
			});
			batch(INSERT_CLICK, clicks, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setLong(i++, r.libraryId());
				ps.setString(i++, text(r.place()));
				ps.setString(i++, text(r.screen()));
				setLong(ps, i++, r.bookId());
				ps.setString(i++, text(r.loginStatus()));
				ps.setString(i++, text(r.clientIp()));
				setLong(ps, i++, r.accountId());
				ps.setString(i, text(r.visitKey()));
			});
			batch(INSERT_SCREEN, screens, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setString(i++, text(r.kind()));
				ps.setString(i++, text(r.picksList()));
				ps.setString(i++, text(r.picksCategory()));
				setLong(ps, i++, r.bookId());
				ps.setArray(i++, r.residenceRegionIds() == null ? null : bigints(ps, r.residenceRegionIds()));
				ps.setArray(i++, r.workRegionIds() == null ? null : bigints(ps, r.workRegionIds()));
				setLong(ps, i++, r.libraryId());
				ps.setString(i++, text(r.loginStatus()));
				ps.setString(i++, text(r.clientIp()));
				setLong(ps, i++, r.accountId());
				ps.setString(i, text(r.visitKey()));
			});
			batch(INSERT_REJECTED, rejected, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setString(i++, text(r.clientIp()));
				ps.setString(i++, text(r.reason()));
				ps.setString(i, text(r.rawValues()));
			});
			batch(INSERT_AI_SEARCH, aiSearches, (ps, r) -> {
				int i = time(ps, 1, r.receivedAt());
				ps.setInt(i++, r.elapsedMs());
				ps.setInt(i++, r.turnNo());
				ps.setString(i++, text(r.keywordResult()));
				ps.setBoolean(i++, r.titleLike());
				ps.setString(i++, text(r.pickResult()));
				ps.setInt(i++, r.candidateCount());
				ps.setArray(i++, bigints(ps, r.pickedBookIds()));
				ps.setInt(i++, r.inputTokens());
				ps.setInt(i, r.outputTokens());
			});
			batch(UPSERT_RESPONSE, answers, (ps, e) -> {
				ResponseKey key = e.getKey();
				ResponseTotals t = e.getValue();
				ps.setObject(1, key.day());
				ps.setLong(2, key.libraryId());
				ps.setString(3, text(key.source().name().toLowerCase(java.util.Locale.ROOT)));
				ps.setInt(4, t.ok());
				ps.setInt(5, t.failed());
				ps.setInt(6, t.timeout());
				ps.setInt(7, t.skipped());
				ps.setLong(8, t.okMsTotal());
				ps.setInt(9, t.okMsMax());
				ps.setInt(10, t.cached());
				ps.setInt(11, t.bodyTooLarge());
			});
			batch(INSERT_DROPPED, dropped, (ps, d) -> {
				time(ps, 1, d.droppedAt());
				ps.setInt(2, (int) Math.min(d.rowCount(), Integer.MAX_VALUE));
				ps.setString(3, text(d.reason()));
			});
		});
	}

	@FunctionalInterface
	private interface Setter<T> {

		void set(PreparedStatement ps, T row) throws SQLException;

	}

	private <T> void batch(String sql, List<T> rows, Setter<T> setter) {
		if (rows.isEmpty()) {
			return;
		}
		jdbc.batchUpdate(sql, rows, rows.size(), setter::set);
	}

	private static String text(String value) {
		return value == null || value.indexOf('\u0000') < 0 ? value : value.replace("\u0000", "");
	}

	private static int time(PreparedStatement ps, int index, Instant at) throws SQLException {
		ps.setTimestamp(index, Timestamp.from(at));
		return index + 1;
	}

	/** title ... library_ids: twelve parameters in table order. */
	private static int conditions(PreparedStatement ps, int index, SearchConditions c) throws SQLException {
		int i = index;
		ps.setString(i++, text(c.title()));
		ps.setString(i++, text(c.author()));
		ps.setString(i++, text(c.publisher()));
		ps.setArray(i++, c.exact() == null ? null : texts(ps, c.exact()));
		ps.setArray(i++, c.roles() == null ? null : texts(ps, c.roles()));
		ps.setString(i++, text(c.sort()));
		ps.setString(i++, text(c.filter()));
		ps.setString(i++, text(c.scope()));
		ps.setString(i++, text(c.visit()));
		ps.setArray(i++, bigints(ps, c.residenceRegionIds()));
		ps.setArray(i++, bigints(ps, c.workRegionIds()));
		ps.setArray(i++, bigints(ps, c.libraryIds()));
		return i;
	}

	private static Array bigints(PreparedStatement ps, List<Long> ids) throws SQLException {
		return ps.getConnection().createArrayOf("bigint", ids.toArray());
	}

	private static Array texts(PreparedStatement ps, List<String> values) throws SQLException {
		return ps.getConnection().createArrayOf("text", values.stream().map(UsageLogWriter::text).toArray());
	}

	private static void setLong(PreparedStatement ps, int index, Long value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.BIGINT);
		}
		else {
			ps.setLong(index, value);
		}
	}

	private static void setInteger(PreparedStatement ps, int index, Integer value) throws SQLException {
		if (value == null) {
			ps.setNull(index, Types.INTEGER);
		}
		else {
			ps.setInt(index, value);
		}
	}

}
