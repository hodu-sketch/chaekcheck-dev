package com.chaekcheck.catalog.internal.texts;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chaekcheck.catalog.AiSearchIndex;

/**
 * {@link AiSearchIndex} with {@code JdbcClient}: the queries the aisearch module ran on {@code aisearch.search_book},
 * on {@code catalog.book_search} (F6). Only {@link #books()} reads {@code catalog.book}, for the titles and authors.
 */
@Component
class JdbcAiSearchIndex implements AiSearchIndex {

	private static final Logger log = LoggerFactory.getLogger(JdbcAiSearchIndex.class);

	private static final String PLAN_COUNT = """
			(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY(CAST(:plan AS integer[])))::int""";

	private final JdbcClient jdbc;

	JdbcAiSearchIndex(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries, Set<Long> excluded,
			int limit) {
		if (words.isEmpty() || planLibraries.isEmpty() || limit < 1) {
			return List.of();
		}
		List<String> score = new ArrayList<>();
		List<String> any = new ArrayList<>();
		List<String> coreScore = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			// only generated parameter names go into the text; the words are bound
			String like = "s.search_text LIKE :k" + i + " ESCAPE '\\'";
			score.add("(" + like + ")::int");
			any.add(like);
			if (coreWords.contains(words.get(i))) {
				coreScore.add("(" + like + ")::int");
			}
		}
		String coreSum = coreScore.isEmpty() ? "0" : String.join(" + ", coreScore);
		JdbcClient.StatementSpec statement = jdbc.sql("""
				SELECT book_id, score, plan_count FROM (
				  SELECT s.book_id, %s AS score, %s AS plan_count, %s AS core_score
				  FROM catalog.book_search s
				  WHERE (%s)
				    AND s.library_ids && CAST(:plan AS integer[])
				    AND NOT (s.book_id = ANY(CAST(:excluded AS bigint[])))
				) m
				ORDER BY core_score DESC, score DESC, plan_count DESC, book_id
				LIMIT :limit""".formatted(String.join(" + ", score), PLAN_COUNT, coreSum, String.join(" OR ", any)))
			.param("plan", planLibraries.toArray(Long[]::new))
			.param("excluded", excluded.toArray(Long[]::new))
			.param("limit", limit);
		for (int i = 0; i < words.size(); i++) {
			statement = statement.param("k" + i, "%" + escapeLike(words.get(i)) + "%");
		}
		return statement.query((rs, n) -> new Candidate(rs.getLong(1), rs.getInt(2), rs.getInt(3))).list();
	}

	@Override
	public Map<Long, Integer> planCounts(Collection<Long> bookIds, Set<Long> planLibraries) {
		if (bookIds.isEmpty() || planLibraries.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new HashMap<>();
		jdbc.sql("""
				SELECT s.book_id, %s AS plan_count FROM catalog.book_search s
				WHERE s.book_id = ANY(CAST(:ids AS bigint[])) AND s.library_ids && CAST(:plan AS integer[])"""
			.formatted(PLAN_COUNT))
			.param("ids", JdbcBookTexts.distinct(bookIds))
			.param("plan", planLibraries.toArray(Long[]::new))
			.query(rs -> {
				counts.put(rs.getLong(1), rs.getInt(2));
			});
		return counts;
	}

	@Override
	public Map<Long, String> lines(Collection<Long> bookIds) {
		if (bookIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> lines = new HashMap<>();
		jdbc.sql("""
				SELECT book_id, line FROM catalog.book_search
				WHERE book_id = ANY(CAST(:ids AS bigint[])) AND line IS NOT NULL""")
			.param("ids", JdbcBookTexts.distinct(bookIds))
			.query(rs -> {
				lines.put(rs.getLong(1), rs.getString(2));
			});
		return lines;
	}

	@Override
	public List<SearchBook> books() {
		return jdbc.sql("""
				SELECT s.book_id, b.title, b.author_display FROM catalog.book_search s
				JOIN catalog.book b ON b.id = s.book_id
				ORDER BY s.book_id""")
			.query((rs, n) -> new SearchBook(rs.getLong(1), rs.getString(2), rs.getString(3)))
			.list();
	}

	@Override
	public String version() {
		return jdbc.sql("SELECT count(*) AS n, max(imported_at) AS at FROM catalog.book_search")
			.query((rs, n) -> rs.getLong("n") + "@" + rs.getObject("at", OffsetDateTime.class))
			.single();
	}

	@Override
	public void compact() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// VACUUM cannot run in a transaction (the import calls this after its own); the space stays until next time
			log.info("search books not compacted: called inside a transaction");
			return;
		}
		long started = System.nanoTime();
		try {
			jdbc.sql("VACUUM (FULL, ANALYZE) catalog.book_search").update();
			log.info("search books compacted in {} ms", (System.nanoTime() - started) / 1_000_000);
		}
		catch (DataAccessException e) {
			// the books are in already; only the space of the replaced rows stays
			log.warn("search books not compacted: {}", e.getClass().getName());
		}
	}

	/** {@code \}, {@code %} and {@code _} are matched as themselves ({@code ESCAPE '\'}). */
	static String escapeLike(String word) {
		return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
