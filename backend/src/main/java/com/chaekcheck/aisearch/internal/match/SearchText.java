package com.chaekcheck.aisearch.internal.match;

/**
 * The AI search's text tidying, kept in one place: {@code catalog.SearchText} since cycle 26, which also makes
 * {@code catalog.book_search.search_text}. The keywords are tidied with the same rule, so they meet. The names here
 * stay for the AI search's own callers.
 */
public final class SearchText {

	private SearchText() {
	}

	/** {@link com.chaekcheck.catalog.SearchText#tidy}. */
	public static String tidy(String text) {
		return com.chaekcheck.catalog.SearchText.tidy(text);
	}

	/** {@link com.chaekcheck.catalog.SearchText#letters}. */
	public static String letters(String text) {
		return com.chaekcheck.catalog.SearchText.letters(text);
	}

	/** {@link com.chaekcheck.catalog.SearchText#ofBook}. */
	public static String ofBook(String title, String author, String line) {
		return com.chaekcheck.catalog.SearchText.ofBook(title, author, line);
	}

}
