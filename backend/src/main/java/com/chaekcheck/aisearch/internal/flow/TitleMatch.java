package com.chaekcheck.aisearch.internal.flow;

import java.util.List;
import java.util.regex.Pattern;

import com.chaekcheck.aisearch.internal.match.SearchText;
import com.chaekcheck.picks.ListedBooks.ListedBook;

/**
 * Whether a title guess is close to one of the listed books (cycle 24 issue 2-1 (나), user 2026-10-25): the first call
 * took topic sentences such as 「세네카와 쇼펜하우어의 철학」 for titles, so the title line is sent only when the guess,
 * compared without spaces, signs and case, is a listed book's title (or at least half of its start), the title followed
 * by part of its author, or part of an author.
 */
final class TitleMatch {

	private static final Pattern NOT_LETTER = Pattern.compile("[^\\p{L}\\p{N}]+");

	private TitleMatch() {
	}

	static boolean close(String guess, List<ListedBook> books) {
		String key = key(guess);
		if (key.codePointCount(0, key.length()) < 2) {
			return false;
		}
		for (ListedBook book : books) {
			String title = key(book.title());
			String author = key(book.author());
			if (title.length() >= 2 && (title.equals(key) || title.startsWith(key) && key.length() * 2 >= title.length()
					|| key.startsWith(title) && !author.isEmpty() && author.contains(key.substring(title.length())))) {
				return true;
			}
			if (author.contains(key)) {
				return true;
			}
		}
		return false;
	}

	private static String key(String text) {
		return NOT_LETTER.matcher(SearchText.tidy(text)).replaceAll("");
	}

}
