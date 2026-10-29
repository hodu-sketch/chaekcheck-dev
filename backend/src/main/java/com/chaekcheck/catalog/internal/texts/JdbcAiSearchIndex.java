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
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.catalog.AiSearchIndex;
import com.chaekcheck.catalog.SearchText;
import com.chaekcheck.catalog.internal.texts.AiSearchTextSettings.KeywordMatch;
import com.chaekcheck.catalog.internal.texts.AiSearchTextSettings.ReadingText;

/**
 * {@link AiSearchIndex} with {@code JdbcClient}: the AI search's queries on
 * {@code catalog.book_search} (F6). Only {@link #books()} reads {@code catalog.book}, for the titles and authors.
 */
@Component
@EnableConfigurationProperties(AiSearchTextSettings.class)
class JdbcAiSearchIndex implements AiSearchIndex {

	private static final Logger log = LoggerFactory.getLogger(JdbcAiSearchIndex.class);

	/**
	 * The book's plan libraries counted by intarray in {@code catalog.plan_library_count} (catalog/004, 2026-10-29): a
	 * sorted-array intersection, about 10 µs a book whatever the order. Best when every matched book must be counted
	 * (one word: all points equal) or the plan is small.
	 */
	private static final String PLAN_COUNT = "catalog.plan_library_count(s.library_ids, CAST(:plan AS integer[]))";

	/**
	 * The same count as a SubPlan over {@code unnest}: the planner sorts by points first and counts only the books the
	 * {@code LIMIT} reaches (about 600 of 16,000), which wins for several words over a large plan.
	 */
	private static final String PLAN_COUNT_LAZY = """
			(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY(CAST(:plan AS integer[])))::int""";

	/**
	 * Above this many plan libraries a question of several words takes {@link #PLAN_COUNT_LAZY}: measured in production
	 * at 30, 200, 1,000 and 2,455 libraries (docs/cycle26-book-descriptions/4-keyword-query-speed.md), the function
	 * won at 200 and lost at 1,000; 500 between them is not measured [안].
	 */
	static final int FUNCTION_PLAN_MAX = 500;

	/**
	 * With 「최신」 the BM25 match reads this many times the candidates and puts the recent ones first, as the vector
	 * side does within its nearest books (AiSearchFlow.RECENT_WINDOW) [안].
	 */
	static final int RECENT_POOL = 2;

	private final JdbcClient jdbc;

	private final TransactionTemplate readOnly;

	private final KeywordMatch keywordMatch;

	private final ReadingText readingText;

	/** Whether the BM25 index is there; null until the first BM25 search asks (Liquibase runs before that). */
	private volatile Boolean bm25Index;

	JdbcAiSearchIndex(JdbcClient jdbc, PlatformTransactionManager transactionManager, AiSearchTextSettings settings) {
		this.jdbc = jdbc;
		this.readOnly = new TransactionTemplate(transactionManager);
		this.readOnly.setReadOnly(true);
		this.keywordMatch = settings.keywordMatch();
		this.readingText = settings.readingText();
	}

	@Override
	public List<Candidate> find(List<String> words, Set<String> coreWords, Set<Long> planLibraries, Set<Long> excluded,
			int limit, Integer fromYear) {
		if (words.isEmpty() || planLibraries.isEmpty() || limit < 1) {
			return List.of();
		}
		if (keywordMatch == KeywordMatch.BM25 && bm25Ready()) {
			return findBm25(words, planLibraries, excluded, limit, fromYear);
		}
		List<String> score = new ArrayList<>();
		List<String> any = new ArrayList<>();
		List<String> coreScore = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			// only generated parameter names go into the text; the words are bound. LIKE finds the books (pg_bigm index);
			// the points use strpos on the plain word, which matches the same books without matching the pattern again
			String like = "s.search_text LIKE :k" + i + " ESCAPE '\\'";
			String has = "(strpos(s.search_text, :w" + i + ") > 0)::int";
			score.add(has);
			any.add(like);
			if (coreWords.contains(words.get(i))) {
				coreScore.add(has);
			}
		}
		String coreSum = coreScore.isEmpty() ? "0" : String.join(" + ", coreScore);
		String planCount = planCountFor(words.size(), planLibraries.size());
		// recent books first among those holding as many words when asked for (the book's date is joined only then);
		// before the words, a recent book holding one common word came before the ones holding the topic (2026-10-29)
		String recent = fromYear == null ? "0"
				: "(SELECT (b.published_on >= make_date(:from, 1, 1))::int FROM catalog.book b WHERE b.id = s.book_id)";
		JdbcClient.StatementSpec statement = jdbc.sql("""
				SELECT book_id, score, plan_count FROM (
				  SELECT s.book_id, %s AS score, %s AS plan_count, %s AS core_score, coalesce(%s, 0) AS recent
				  FROM catalog.book_search s
				  WHERE (%s)
				    AND s.library_ids && CAST(:plan AS integer[])
				    AND NOT (s.book_id = ANY(CAST(:excluded AS bigint[])))
				) m
				ORDER BY core_score DESC, score DESC, recent DESC, plan_count DESC, book_id
				LIMIT :limit""".formatted(String.join(" + ", score), planCount, coreSum, recent, String.join(" OR ", any)))
			.param("plan", planLibraries.toArray(Long[]::new))
			.param("excluded", excluded.toArray(Long[]::new))
			.param("limit", limit);
		if (fromYear != null) {
			statement = statement.param("from", fromYear);
		}
		for (int i = 0; i < words.size(); i++) {
			statement = statement.param("k" + i, "%" + escapeLike(words.get(i)) + "%").param("w" + i, words.get(i));
		}
		JdbcClient.StatementSpec query = statement;
		// a custom plan every time (2026-10-29, dev 24's timing after deploy 1): after five runs of the same text on
		// one connection the driver's prepared statement gets PostgreSQL's generic plan, which counts the plan
		// libraries of every matched row (16,418 rows, 5.2 s for the words 소설, 위로) instead of the top ones (0.24 s)
		return readOnly.execute(status -> {
			jdbc.sql("SET LOCAL plan_cache_mode = force_custom_plan").update();
			return query.query((rs, n) -> new Candidate(rs.getLong(1), rs.getInt(2), rs.getInt(3))).list();
		});
	}

	/**
	 * BM25 (pg_search, catalog/006) over {@code keyword_text}: title, author, line and the Haiku search terms, read by
	 * the Korean analyser (user 2026-10-30). The plan-library filter is a zero-weight clause on {@code library_words}
	 * inside the index, so pg_search keeps only the top books (7 against 125 ms at 100,000 rows with {@code &&} outside
	 * it, docs/aisearch/reports/2026-10-30-vector-scaling.md 10). The books shown before are left out here, not in SQL,
	 * for the same reason. The core words are not weighted more: the measured BM25 had none [안]. The points are the
	 * BM25 score times 1,000.
	 */
	private List<Candidate> findBm25(List<String> words, Set<Long> planLibraries, Set<Long> excluded, int limit,
			Integer fromYear) {
		int read = (fromYear == null ? limit : limit * RECENT_POOL) + excluded.size();
		StringBuilder libraries = new StringBuilder();
		for (long id : planLibraries) {
			libraries.append(libraries.isEmpty() ? "l" : " l").append(id);
		}
		String recent = fromYear == null ? "false" : """
				coalesce((SELECT b.published_on >= make_date(:from, 1, 1) FROM catalog.book b WHERE b.id = s.book_id),
				  false)""";
		JdbcClient.StatementSpec statement = jdbc.sql("""
				SELECT s.book_id, pdb.score(s.book_id) AS score, %s AS plan_count, %s AS recent
				FROM catalog.book_search s
				WHERE s.keyword_text ||| :words AND s.library_words ||| CAST(:libraries AS text)::pdb.boost(0)
				ORDER BY pdb.score(s.book_id) DESC
				LIMIT :limit""".formatted(PLAN_COUNT, recent))
			.param("words", String.join(" ", words))
			.param("libraries", libraries.toString())
			.param("plan", planLibraries.toArray(Long[]::new))
			.param("limit", read);
		if (fromYear != null) {
			statement = statement.param("from", fromYear);
		}
		JdbcClient.StatementSpec query = statement;
		List<Candidate> recentFirst = new ArrayList<>();
		List<Candidate> older = new ArrayList<>();
		readOnly.execute(status -> {
			query.query(rs -> {
				long bookId = rs.getLong(1);
				if (!excluded.contains(bookId)) {
					Candidate candidate = new Candidate(bookId, Math.round(rs.getFloat(2) * 1000), rs.getInt(3));
					(rs.getBoolean(4) ? recentFirst : older).add(candidate);
				}
			});
			return null;
		});
		recentFirst.addAll(older);
		return recentFirst.subList(0, Math.min(limit, recentFirst.size()));
	}

	/** Whether {@code catalog.book_search_bm25} exists; asked once, a missing index logged once. */
	private boolean bm25Ready() {
		Boolean ready = bm25Index;
		if (ready == null) {
			ready = jdbc.sql("SELECT to_regclass('catalog.book_search_bm25') IS NOT NULL").query(Boolean.class).single();
			if (!ready) {
				log.warn("ai search keywords: BM25 is set but catalog.book_search_bm25 is missing (no pg_search in the "
						+ "database image?); LIKE is used");
			}
			bm25Index = ready;
		}
		return ready;
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
	public Map<Long, String> readingTexts(Collection<Long> bookIds, List<String> words) {
		if (readingText == ReadingText.LINE || bookIds.isEmpty()) {
			return lines(bookIds);
		}
		Map<Long, String> texts = new HashMap<>();
		jdbc.sql("""
				SELECT book_id, line, summary, terms FROM catalog.book_search
				WHERE book_id = ANY(CAST(:ids AS bigint[])) AND (line IS NOT NULL OR summary IS NOT NULL)""")
			.param("ids", JdbcBookTexts.distinct(bookIds))
			.query(rs -> {
				String summary = rs.getString(3);
				if (summary == null) {
					texts.put(rs.getLong(1), rs.getString(2));
					return;
				}
				List<String> shared = sharedTerms(rs.getString(4), words);
				texts.put(rs.getLong(1),
						shared.isEmpty() ? summary : summary + " / 검색 낱말: " + String.join(", ", shared));
			});
		return texts;
	}

	/**
	 * The search terms that share a question word: one holds the other after {@link SearchText#tidy}, or they share two
	 * letters in a row (the measured rule, scripts/aisearch/eval/terms_onecall_eval.py {@code matched}).
	 */
	static List<String> sharedTerms(String terms, List<String> words) {
		if (terms == null || words.isEmpty()) {
			return List.of();
		}
		List<String> tidied = words.stream().map(SearchText::tidy).filter(w -> !w.isEmpty()).toList();
		List<String> out = new ArrayList<>();
		for (String raw : terms.split(",")) {
			String term = raw.strip();
			String tidy = SearchText.tidy(term);
			if (tidy.isEmpty() || out.contains(term)) {
				continue;
			}
			for (String word : tidied) {
				if (word.contains(tidy) || tidy.contains(word) || sharesTwoLetters(word, tidy)) {
					out.add(term);
					break;
				}
			}
		}
		return out;
	}

	private static boolean sharesTwoLetters(String word, String term) {
		for (int i = 0; i + 2 <= word.length(); i++) {
			if (term.contains(word.substring(i, i + 2))) {
				return true;
			}
		}
		return false;
	}

	@Override
	public List<SearchBook> books() {
		return jdbc.sql("""
				SELECT s.book_id, b.title, b.author_display, extract(year FROM b.published_on)::int AS year
				FROM catalog.book_search s
				JOIN catalog.book b ON b.id = s.book_id
				ORDER BY s.book_id""")
			.query((rs, n) -> new SearchBook(rs.getLong(1), rs.getString(2), rs.getString(3),
					rs.getObject(4, Integer.class)))
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
	/** The plan library count for a question of this many words over this many plan libraries. */
	static String planCountFor(int words, int planLibraries) {
		return words == 1 || planLibraries <= FUNCTION_PLAN_MAX ? PLAN_COUNT : PLAN_COUNT_LAZY;
	}

	static String escapeLike(String word) {
		return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
