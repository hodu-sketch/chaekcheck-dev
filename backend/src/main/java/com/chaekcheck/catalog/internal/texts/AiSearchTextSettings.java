package com.chaekcheck.catalog.internal.texts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chaekcheck.catalog.ai-search.*}: which of {@code catalog.book_search}'s texts the AI search uses (user
 * 2026-10-30 "두 층 (조각 512) + BM25 이걸로 개발해", "한 줄 + Haiku 검색 낱말 (원문 불필요)로 가자"; measured in
 * docs/features/aisearch/reports/2026-10-29-intro-text.md 10-11).
 * @param keywordMatch {@link KeywordMatch#LIKE} (v1.15.0 on) or {@link KeywordMatch#BM25}; BM25 falls back to LIKE with
 *     one warning while the database has no {@code catalog.book_search_bm25} index (no pg_search)
 * @param readingText what the second call reads for a candidate: {@link ReadingText#LINE} (v1.15.0 on) or
 *     {@link ReadingText#SUMMARY}
 */
@ConfigurationProperties("chaekcheck.catalog.ai-search")
public record AiSearchTextSettings(KeywordMatch keywordMatch, ReadingText readingText) {

	public AiSearchTextSettings {
		keywordMatch = keywordMatch == null ? KeywordMatch.LIKE : keywordMatch;
		readingText = readingText == null ? ReadingText.LINE : readingText;
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

	/** What the second call reads after a candidate's title and author. */
	public enum ReadingText {

		/** The search line (about 100 characters). */
		LINE,

		/**
		 * The Haiku summary and the search terms that share a question word ("요약 / 검색 낱말: a, b"); the line for a
		 * book without a summary. Picked 93.5% / first 76.5% against the line's 68.0% / 60.3% (400 known-book queries).
		 */
		SUMMARY

	}

}
