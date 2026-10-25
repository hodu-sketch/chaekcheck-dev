package com.chaekcheck.aisearch.internal.flow;

import java.util.List;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;
import com.chaekcheck.aisearch.internal.match.SearchText;

/**
 * Whether a title guess is close to one of the search books (the listed books and, since 2026-10-25, the books held by
 * the most libraries) (cycle 24 issue 2-1 (나), user 2026-10-25): the first call
 * took topic sentences such as 「세네카와 쇼펜하우어의 철학」 for titles, so the title line is sent only when the guess,
 * compared without spaces, signs and case, is a search book's title (or at least half of its start), the title followed
 * by part of its author, or part of an author.
 */
final class TitleMatch {

	private TitleMatch() {
	}

	static boolean close(String guess, List<SearchBook> books) {
		String key = SearchText.letters(guess);
		if (key.codePointCount(0, key.length()) < 2) {
			return false;
		}
		for (SearchBook book : books) {
			String title = book.titleKey();
			String author = book.authorKey();
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

}
