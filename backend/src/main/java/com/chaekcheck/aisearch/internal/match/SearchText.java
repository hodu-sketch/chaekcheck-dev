package com.chaekcheck.aisearch.internal.match;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one way the AI search tidies text for the keyword match (handoff step 4-2 [안]): NFKC, lower case, every space
 * and every invisible format character (zero-width space, byte order mark, …) removed. {@code search_book.search_text} and the keywords (step 5) are both made with it, so they meet.
 */
public final class SearchText {

	private static final Pattern SPACES = Pattern.compile("[\\s\\p{Cf}]+", Pattern.UNICODE_CHARACTER_CLASS);

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
