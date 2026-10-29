package com.chaekcheck.catalog.internal.texts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chaekcheck.catalog.ai-search.*}: how the AI search's keyword candidates are matched (user 2026-10-30 "두 층
 * (조각 512) + BM25 이걸로 개발해", measured in docs/aisearch/reports/2026-10-29-intro-text.md 10-11).
 * @param keywordMatch {@link KeywordMatch#LIKE} (v1.15.0 on) or {@link KeywordMatch#BM25}; BM25 falls back to LIKE with
 *     one warning while the database has no {@code catalog.book_search_bm25} index (no pg_search)
 */
@ConfigurationProperties("chaekcheck.catalog.ai-search")
public record KeywordMatchSettings(KeywordMatch keywordMatch) {

	public KeywordMatchSettings {
		keywordMatch = keywordMatch == null ? KeywordMatch.LIKE : keywordMatch;
	}

	/** How the keyword candidates are matched. */
	public enum KeywordMatch {

		/** {@code search_text LIKE} each word (pg_bigm), books ordered by the core and other words they hold. */
		LIKE,

		/**
		 * pg_search BM25 over title, author, line and the Haiku search terms ({@code keyword_text}, Korean analyser),
		 * the plan-library filter inside the index.
		 */
		BM25

	}

}
