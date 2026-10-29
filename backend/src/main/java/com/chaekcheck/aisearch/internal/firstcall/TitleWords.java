package com.chaekcheck.aisearch.internal.firstcall;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Whether a word ending in 나 or 랑 is a word of its own (「코로나」, 「바르셀로나」, 「베테랑」, 「누구나」) and not a topic
 * with a joining ending (「세계역사나」): it is when a title of the search books holds it (the evaluation session,
 * 2026-10-29: cutting every 나 turned 코로나 into 코로 in 29 of 857 test queries). The titles are the ones
 * {@code SearchBooks} read last; before that nothing is known and the ending comes off as before.
 */
public final class TitleWords {

	/** Answers kept at most; past that the cache starts again (the words of a few questions a minute). */
	private static final int CACHE_MAX = 10_000;

	private static volatile List<String> titles = List.of();

	private static final Map<String, Boolean> known = new ConcurrentHashMap<>();

	private TitleWords() {
	}

	/** The titles to look words up in, lower case; called when the search books are read. */
	public static void use(List<String> lowerCaseTitles) {
		titles = List.copyOf(lowerCaseTitles);
		known.clear();
	}

	/** Whether a title holds the word (lower case). */
	public static boolean known(String word) {
		List<String> current = titles;
		if (current.isEmpty()) {
			return false;
		}
		if (known.size() > CACHE_MAX) {
			known.clear();
		}
		return known.computeIfAbsent(word, w -> current.stream().anyMatch(title -> title.contains(w)));
	}

}
