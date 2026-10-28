package com.chaekcheck.catalog;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one way the AI search tidies text for the keyword match (cycle 24 handoff step 4-2; in catalog since cycle 26,
 * which makes {@code catalog.book_search.search_text}): NFKC, lower case, every space and every invisible format
 * character (zero-width space, byte order mark, …) removed. The search text and the keywords are both made with it,
 * so they meet.
 */
public final class SearchText {

	private static final Pattern SPACES = Pattern.compile("[\\s\\p{Cf}]+", Pattern.UNICODE_CHARACTER_CLASS);

	private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");

	private SearchText() {
	}

	/** The tidied text; null and blank give an empty string. */
	public static String tidy(String text) {
		if (text == null) {
			return "";
		}
		String folded = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		return SPACES.matcher(folded).replaceAll("");
	}

	/** {@link #tidy} with every sign left out too: letters and digits only (the title line check, issue 2-1). */
	public static String letters(String text) {
		return NOT_LETTER.matcher(tidy(text)).replaceAll("");
	}

	/**
	 * {@code search_text} of a book: title, author and line, each tidied, joined by one space. A keyword never holds a
	 * space after {@link #tidy}, so no keyword can match across two parts (for example the end of a title and the
	 * start of the author).
	 */
	public static String ofBook(String title, String author, String line) {
		StringBuilder text = new StringBuilder(tidy(title));
		String tidiedAuthor = tidy(author);
		if (!tidiedAuthor.isEmpty()) {
			text.append(' ').append(tidiedAuthor);
		}
		String tidiedLine = tidy(line);
		if (!tidiedLine.isEmpty()) {
			text.append(' ').append(tidiedLine);
		}
		return text.toString();
	}

}
