package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;
import com.chaekcheck.aisearch.internal.match.SearchText;

/**
 * The book a "○○ 같은 책" question names (user 2026-10-25, docs/aisearch/reports/2026-10-25-eval.md 3-3: the named
 * book's neighbours first raised 「아주 맞음」 from 22% to 42%). A search book is named when its title, letters only
 * and up to the first bracket, colon or full stop, comes right before 같은, 비슷, 처럼, 류의 or 풍의 in the question
 * (a 와·과·랑·이랑·하고 between them is allowed), so a title met elsewhere in a sentence is not taken. The longest such
 * title wins; every edition with that title is the book.
 * @param key the title's letters
 * @param editions the search books with that title
 */
record NamedBook(String key, List<Long> editions) {

	private static final Pattern CUE = Pattern.compile("같은|비슷|처럼|류의|풍의");

	private static final Pattern PARTICLE = Pattern.compile("(이랑|하고|와|과|랑)$");

	private static final Pattern TITLE_END = Pattern.compile("[(\\[.:]");

	/** Shortest title taken, in characters. */
	static final int MIN_TITLE = 2;

	/** Longest title tried. */
	static final int MAX_TITLE = 40;

	/** The titles of one books list, made once per list (the list is replaced when the import changes the books). */
	private static volatile Index index = new Index(List.of(), Map.of());

	private record Index(List<SearchBook> books, Map<String, List<Long>> byKey) {
	}

	static String key(String title) {
		String head = TITLE_END.split(title == null ? "" : title, 2)[0];
		return SearchText.letters(head);
	}

	/** The book the question names, or null. */
	static NamedBook find(String question, List<SearchBook> books) {
		Map<String, List<Long>> byKey = byKey(books);
		String text = SearchText.letters(question);
		NamedBook best = null;
		Matcher cue = CUE.matcher(text);
		while (cue.find()) {
			String before = text.substring(0, cue.start());
			// a title may end with 와 itself, so the text is tried as it is and without the particle
			for (String head : List.of(before, PARTICLE.matcher(before).replaceFirst(""))) {
				for (int length = Math.min(MAX_TITLE, head.length()); length >= MIN_TITLE; length--) {
					String key = head.substring(head.length() - length);
					List<Long> editions = byKey.get(key);
					if (editions != null) {
						if (best == null || key.length() > best.key().length()) {
							best = new NamedBook(key, editions);
						}
						break;
					}
				}
			}
		}
		return best;
	}

	/**
	 * The editions of the titles a question that is a title names (「해리포터」, user 2026-10-29): the titles whose letters
	 * start with the question's letters, the shortest title first (the title itself before its other volumes); at most
	 * {@code max}.
	 */
	static List<Long> titled(String question, List<SearchBook> books, int max) {
		String text = SearchText.letters(question);
		if (text.length() < MIN_TITLE) {
			return List.of();
		}
		List<String> keys = byKey(books).keySet().stream().filter(k -> k.startsWith(text)).sorted(
				java.util.Comparator.comparingInt(String::length).thenComparing(java.util.Comparator.naturalOrder())).toList();
		List<Long> ids = new ArrayList<>();
		for (String key : keys) {
			for (long id : byKey(books).get(key)) {
				if (ids.size() < max) {
					ids.add(id);
				}
			}
		}
		return ids;
	}

	private static Map<String, List<Long>> byKey(List<SearchBook> books) {
		Index current = index;
		if (current.books() != books) {
			Map<String, List<Long>> byKey = new HashMap<>();
			for (SearchBook book : books) {
				String key = key(book.title());
				if (key.length() >= MIN_TITLE) {
					byKey.computeIfAbsent(key, k -> new ArrayList<>()).add(book.bookId());
				}
			}
			current = new Index(books, byKey);
			index = current;
		}
		return current.byKey();
	}

}
