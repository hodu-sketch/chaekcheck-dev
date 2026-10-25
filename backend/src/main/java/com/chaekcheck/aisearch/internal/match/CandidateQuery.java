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
 * The keyword match (cycle 24 handoff step 5, design-delta "낱말 맞추기", ADR-0151): which of the target books hold the
 * keywords in {@code aisearch.search_book.search_text}. One point per keyword found; ordered by points, then by how
 * many of the user's plan libraries hold the book, then by book id, and cut after the order (design review #5: cutting
 * first would drop tied books by id). The plan library counts are made in memory by {@link PlanTargets} and passed in
 * with the ids ({@code unnest}), so the query never reads another module's table.
 */
@Component
public class CandidateQuery {

	/** Keywords used at most (design-delta [안]); the rest are dropped. */
	public static final int MAX_KEYWORDS = 8;

	/** Core words used at most (cycle 24 1단계 [안], the first call is asked for 1 to 4). */
	public static final int MAX_CORE_WORDS = 4;

	/** Shortest keyword after tidying, in characters (design-delta [안]); pg_bigm needs two for its index. */
	public static final int MIN_KEYWORD_CHARS = 2;

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
	 * The matched books, best first, at most {@code limit}. Targets held by no plan library are left out (spec R3-3);
	 * no usable keyword or no target gives an empty list without a query.
	 */
	public List<Candidate> find(List<String> keywords, List<PlanTargets.Target> targets, int limit) {
		return find(keywords, List.of(), targets, limit);
	}

	/**
	 * As {@link #find(List, List, int)}, with the question's core words (cycle 24 1단계, user 2026-10-25: a diet
	 * question brought an anticancer diet book on 식단 alone). Books holding more core words come first, then the usual
	 * order; books without one still fill the rest (only ruling them out left a children's question with parenting
	 * books alone, the 2026-10-25 comparison), and the second call's fit weeds out what does not fit.
	 */
	public List<Candidate> find(List<String> keywords, List<String> core, List<PlanTargets.Target> targets, int limit) {
		// the core words come on top of the eight keywords, so they never push a keyword out
		List<String> coreWords = usableKeywords(core).stream().limit(MAX_CORE_WORDS).toList();
		LinkedHashSet<String> joined = new LinkedHashSet<>(coreWords);
		joined.addAll(usableKeywords(keywords));
		List<String> words = List.copyOf(joined);
		List<PlanTargets.Target> held = targets.stream().filter(target -> target.planCount() >= 1).toList();
		if (words.isEmpty() || held.isEmpty() || limit < 1) {
			return List.of();
		}
		Long[] ids = new Long[held.size()];
		Integer[] counts = new Integer[held.size()];
		for (int i = 0; i < held.size(); i++) {
			ids[i] = held.get(i).bookId();
			counts[i] = held.get(i).planCount();
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
				SELECT s.book_id, %s AS score, e.plan_count, %s AS core_score
				FROM aisearch.search_book s
				JOIN unnest(CAST(:ids AS bigint[]), CAST(:counts AS integer[])) AS e(book_id, plan_count) USING (book_id)
				WHERE %s
				ORDER BY core_score DESC, score DESC, e.plan_count DESC, s.book_id
				LIMIT :limit""".formatted(String.join(" + ", score), coreSum, String.join(" OR ", any)))
			.param("ids", ids)
			.param("counts", counts)
			.param("limit", limit);
		for (int i = 0; i < words.size(); i++) {
			statement = statement.param("k" + i, "%" + escapeLike(words.get(i)) + "%");
		}
		return statement.query((rs, n) -> new Candidate(rs.getLong(1), rs.getInt(2), rs.getInt(3))).list();
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
