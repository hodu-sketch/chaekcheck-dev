package com.chaekcheck.aisearch.internal.match;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The keyword match (cycle 24 handoff step 5, design-delta "낱말 맞추기", ADR-0151): which of the search books held by
 * at least one of the user's plan libraries (spec R3-3) hold the keywords in {@code aisearch.search_book.search_text}.
 * One point per keyword found; ordered by core words found (since v1.10.2), points, then by how many plan libraries hold
 * the book, then by book id, and
 * cut after the order (design review #5: cutting first would drop tied books by id). Since 2026-10-25 the plan library
 * count is made in SQL from the {@code library_ids} the import copied (user choice "넣기 때 search_book 사본에"), for the
 * listed books and the books held by the most libraries alike (user choice "한 풀, 구분 없음").
 */
@Component
public class CandidateQuery {

	/** Keywords used at most (design-delta [안]); the rest are dropped. */
	public static final int MAX_KEYWORDS = 8;

	/** Core words used at most (cycle 24 1단계 [안], the first call is asked for 1 to 4). */
	public static final int MAX_CORE_WORDS = 4;

	/** Shortest keyword after tidying, in characters (design-delta [안]); pg_bigm needs two for its index. */
	public static final int MIN_KEYWORD_CHARS = 2;

	private static final String PLAN_COUNT = """
			(SELECT count(*) FROM unnest(s.library_ids) AS l(id) WHERE l.id = ANY(CAST(:plan AS bigint[])))::int""";

	private final JdbcClient jdbc;

	CandidateQuery(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/**
	 * @param bookId a matched book
	 * @param score how many keywords it holds
	 * @param planCount how many plan libraries hold it
	 */
	public record Candidate(long bookId, int score, int planCount) {
	}

	/**
	 * The matched books, best first, at most {@code limit}. Books held by no plan library and the excluded books are
	 * left out; no usable keyword or no plan library gives an empty list without a query.
	 * @param excluded books not to answer with (shown before, spec R11-4, or already chosen)
	 */
	public List<Candidate> find(List<String> keywords, Set<Long> planLibraries, Set<Long> excluded, int limit) {
		return find(keywords, List.of(), planLibraries, excluded, limit);
	}

	/**
	 * As {@link #find(List, Set, Set, int)}, with the question's core words (cycle 24 1단계, user 2026-10-25: a diet
	 * question brought an anticancer diet book on 식단 alone). Books holding more core words come first, then the usual
	 * order; books without one still fill the rest (only ruling them out left a children's question with parenting
	 * books alone, the 2026-10-25 comparison), and the second call's fit weeds out what does not fit.
	 */
	public List<Candidate> find(List<String> keywords, List<String> core, Set<Long> planLibraries, Set<Long> excluded,
			int limit) {
		// the core words come on top of the eight keywords, so they never push a keyword out
		List<String> coreWords = usableKeywords(core).stream().limit(MAX_CORE_WORDS).toList();
		LinkedHashSet<String> joined = new LinkedHashSet<>(coreWords);
		joined.addAll(usableKeywords(keywords));
		List<String> words = List.copyOf(joined);
		if (words.isEmpty() || planLibraries.isEmpty() || limit < 1) {
			return List.of();
		}
		List<String> score = new ArrayList<>();
		List<String> any = new ArrayList<>();
		List<String> coreScore = new ArrayList<>();
		for (int i = 0; i < words.size(); i++) {
			// only generated parameter names go into the text; the keywords are bound
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
				  FROM aisearch.search_book s
				  WHERE (%s)
				    AND s.library_ids && CAST(:plan AS bigint[])
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

	/**
	 * How many plan libraries hold each of the given search books; books held by none (or not stored) are left out.
	 * One query.
	 */
	public Map<Long, Integer> planCounts(Collection<Long> bookIds, Set<Long> planLibraries) {
		if (bookIds.isEmpty() || planLibraries.isEmpty()) {
			return Map.of();
		}
		Map<Long, Integer> counts = new HashMap<>();
		jdbc.sql("""
				SELECT s.book_id, %s AS plan_count FROM aisearch.search_book s
				WHERE s.book_id = ANY(CAST(:ids AS bigint[])) AND s.library_ids && CAST(:plan AS bigint[])"""
			.formatted(PLAN_COUNT))
			.param("ids", bookIds.toArray(Long[]::new))
			.param("plan", planLibraries.toArray(Long[]::new))
			.query(rs -> {
				counts.put(rs.getLong(1), rs.getInt(2));
			});
		return counts;
	}

	/** The search lines of the given books (books without one are left out); one query. */
	public Map<Long, String> lines(Collection<Long> bookIds) {
		if (bookIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> lines = new HashMap<>();
		jdbc.sql("""
				SELECT book_id, line FROM aisearch.search_book
				WHERE book_id = ANY(CAST(:ids AS bigint[])) AND line IS NOT NULL""")
			.param("ids", bookIds.toArray(Long[]::new))
			.query(rs -> {
				lines.put(rs.getLong(1), rs.getString(2));
			});
		return lines;
	}

	/**
	 * The keywords as matched: tidied like {@code search_text} ({@link SearchText#tidy}), at least
	 * {@value #MIN_KEYWORD_CHARS} characters, each once, the first {@value #MAX_KEYWORDS}.
	 */
	public static List<String> usableKeywords(List<String> keywords) {
		Set<String> words = new LinkedHashSet<>();
		for (String keyword : keywords) {
			String word = SearchText.tidy(keyword);
			if (word.codePointCount(0, word.length()) >= MIN_KEYWORD_CHARS) {
				words.add(word);
			}
			if (words.size() == MAX_KEYWORDS) {
				break;
			}
		}
		return List.copyOf(words);
	}

	/** {@code \}, {@code %} and {@code _} are matched as themselves ({@code ESCAPE '\'}). */
	static String escapeLike(String word) {
		return word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
	}

}
