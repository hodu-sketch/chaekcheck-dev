package com.chaekcheck.catalog;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * Public API of the catalog module for the {@code collection} import (cycle 26, ADR-0161): writes the book
 * descriptions the crawl PC made. Only {@code collection} calls it.
 */
public interface BookTextsTransfer {

	/** The longest summary and search line, in characters; the database checks it too. */
	int TEXT_MAX_CHARS = 300;

	/** The longest AI search summary (two layers, 2026-10-30), in characters; the database checks it too. */
	int SEARCH_SUMMARY_MAX_CHARS = 500;

	/** The longest search terms text, in characters; the database checks it too. */
	int SEARCH_TERMS_MAX_CHARS = 2000;

	/** Adds or changes the given summaries in one transaction. @return rows written */
	int upsertSummaries(Collection<Summary> summaries);

	/** Adds or changes the given edition covers in one transaction. @return rows written */
	int upsertCovers(Collection<Cover> covers);

	/**
	 * Replaces every tag of the book with {@code codes}. Codes that are not in the dictionary are skipped.
	 * @return how many of {@code codes} were skipped
	 */
	int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn);

	/**
	 * Adds or changes the given dictionary rows and makes every other known code inactive; no code is ever deleted,
	 * so tags already given keep their meaning.
	 * @return how many codes were active before but not in {@code rows}
	 */
	int replaceTagDictionary(Collection<DictionaryRow> rows);

	/**
	 * Replaces every profile in one transaction.
	 * @throws IllegalArgumentException when {@code profiles} is empty or a book comes twice; nothing is changed
	 * @return rows written
	 */
	int replaceProfiles(Collection<BookProfile> profiles);

	/**
	 * Replaces every row of {@code catalog.book_search} in one transaction: one row per given book that
	 * {@code catalog.book} has, with its line when {@code lines} has one. The title and author of the search text and
	 * the library ids are taken from {@code catalog.book} now (ADR-0160).
	 * @param bookIds every book the AI search answers with, each once; never empty
	 * @throws IllegalArgumentException when {@code bookIds} is empty, or a book or a line's book comes twice; nothing is
	 *     changed
	 * @throws IllegalStateException when {@code lines} is empty but the stored rows have lines (a header-only or wrong
	 *     file would remove every line); nothing is changed
	 */
	default SearchResult replaceSearchBooks(List<Long> bookIds, List<Line> lines) {
		return replaceSearchBooks(bookIds, lines, List.of());
	}

	/**
	 * As {@link #replaceSearchBooks(List, List)}, with the summary and search terms Haiku wrote for the AI search's
	 * two layers (user 2026-10-30). The BM25 text is the title, author, line and terms.
	 * @throws IllegalArgumentException also when a book has two written rows
	 * @throws IllegalStateException also when {@code written} is empty but stored rows have a summary or terms (an
	 *     older file would remove them all); nothing is changed
	 */
	SearchResult replaceSearchBooks(List<Long> bookIds, List<Line> lines, List<Written> written);

	/**
	 * What one Haiku call on the PC wrote for a book's AI search (scripts/aisearch/build_summary_terms.py); the intro it
	 * read stays on the PC (cycle 23 [확정]).
	 * @param summary 1 to {@value BookTextsTransfer#SEARCH_SUMMARY_MAX_CHARS} characters, or null
	 * @param terms comma separated search terms, 1 to {@value BookTextsTransfer#SEARCH_TERMS_MAX_CHARS} characters, or
	 *     null
	 */
	record Written(long bookId, LocalDate madeOn, String summary, String terms) {

		public Written {
			requireBook(bookId);
			if (madeOn == null || (summary == null && terms == null)) {
				throw new IllegalArgumentException("a written row needs a date and a summary or terms: " + bookId);
			}
			requireLength(bookId, "summary", summary, SEARCH_SUMMARY_MAX_CHARS);
			requireLength(bookId, "terms", terms, SEARCH_TERMS_MAX_CHARS);
		}

	}

	/** @param text 1 to {@value BookTextsTransfer#TEXT_MAX_CHARS} characters */
	record Summary(long bookId, String text, LocalDate madeOn) {

		public Summary {
			requireBook(bookId);
			requireText(bookId, text);
			if (madeOn == null) {
				throw new IllegalArgumentException("a summary needs a date: " + bookId);
			}
		}

	}

	/** @param fileKey the picture's sha1, 40 lower-case hex digits */
	record Cover(long bookId, String fileKey) {

		public Cover {
			requireBook(bookId);
			if (fileKey == null || !fileKey.matches("[0-9a-f]{40}")) {
				throw new IllegalArgumentException("a cover key must be 40 lower-case hex digits: " + bookId);
			}
		}

	}

	/**
	 * @param axis {@code mood}, {@code subject} or {@code audience}
	 * @param themaCode the Thema code the tag stands for, or null
	 */
	record DictionaryRow(String code, String axis, String name, String themaCode) {
	}

	record BookProfile(long bookId, BookProfiles.Profile profile) {

		public BookProfile {
			requireBook(bookId);
			if (profile == null) {
				throw new IllegalArgumentException("a profile row needs a profile: " + bookId);
			}
		}

	}

	/**
	 * @param source how it was made
	 * @param madeOn the day the PC batch made it
	 * @param text the line, 1 to {@value BookTextsTransfer#TEXT_MAX_CHARS} characters
	 */
	record Line(long bookId, Source source, LocalDate madeOn, String text) {

		public Line {
			requireBook(bookId);
			if (source == null || madeOn == null) {
				throw new IllegalArgumentException("a line needs a source, a date and a text: " + bookId);
			}
			requireText(bookId, text);
		}

	}

	/** How a search line was made: from the library's book introduction, or from the bibliographic data only. */
	enum Source {

		INTRO("intro"), BIBLIOGRAPHIC("bibliographic");

		private final String code;

		Source(String code) {
			this.code = code;
		}

		/** The value in the TSV file and in {@code book_search.line_source}. */
		public String code() {
			return code;
		}

		/** @throws IllegalArgumentException for any other value */
		public static Source of(String code) {
			for (Source source : values()) {
				if (source.code.equals(code)) {
					return source;
				}
			}
			throw new IllegalArgumentException("unknown line source: " + code);
		}

	}

	/**
	 * @param stored rows written
	 * @param withLine of those, rows with a line
	 * @param notInCatalog given books that {@code catalog.book} does not have (skipped)
	 * @param linesNotListed lines skipped because their book is not stored
	 */
	record SearchResult(int stored, int withLine, int notInCatalog, int linesNotListed) {
	}

	private static void requireBook(long bookId) {
		if (bookId < 1) {
			throw new IllegalArgumentException("a book id must be 1 or more: " + bookId);
		}
	}

	private static void requireLength(long bookId, String what, String text, int max) {
		if (text == null) {
			return;
		}
		int length = text.codePointCount(0, text.length());
		if (text.isBlank() || length > max) {
			throw new IllegalArgumentException(
					"a " + what + " must have 1 to " + max + " characters, book " + bookId + " has " + length);
		}
	}

	private static void requireText(long bookId, String text) {
		int length = text == null ? 0 : text.codePointCount(0, text.length());
		if (text == null || text.isBlank() || length > TEXT_MAX_CHARS) {
			throw new IllegalArgumentException(
					"a text must have 1 to " + TEXT_MAX_CHARS + " characters, book " + bookId + " has " + length);
		}
	}

}
