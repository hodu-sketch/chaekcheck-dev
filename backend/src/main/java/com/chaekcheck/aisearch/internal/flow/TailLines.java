package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tools.jackson.databind.JsonNode;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;

/**
 * The summary by situation, written by the server from what the pick call gave (user 2026-10-29 「바꿔 봐」): the call
 * names situations and the list's numbers ({@code {"lines": [{"situation", "nos"}], "summary"}}), the server puts in
 * the titles of the cards shown. A number of a book not shown is dropped, a book goes in one line only, a line left
 * without a book is dropped; with no line the one-or-two-sentence summary is used when it names no book. In production
 * v1.21.0–v1.21.2 the free text named the list's numbers (「1, 16, 18번」) or books not shown in half the answers.
 */
final class TailLines {

	/** Lines at most. */
	static final int MAX_LINES = 4;

	/** A situation's characters at most. */
	static final int MAX_SITUATION_CHARS = 40;

	/** The summary's characters at most. */
	static final int MAX_SUMMARY_CHARS = 300;

	/** The list's numbers in a summary (「3번」), not 「두 번째」. */
	static final Pattern NUMBERS = Pattern.compile("\\d+\\s*번(?!째)");

	private TailLines() {
	}

	/**
	 * The summary to send, one situation a line (「상황: 『제목』, 『제목』」), or null for none.
	 * @param tail the answer's {@code tail} object
	 * @param ids the candidates in the order the call saw them (number n is {@code ids.get(n - 1)})
	 * @param shown the books shown as cards
	 */
	static String render(JsonNode tail, List<Long> ids, List<Long> shown, Map<Long, SearchBook> known) {
		if (tail == null || !tail.isObject()) {
			return null;
		}
		List<String> lines = new ArrayList<>();
		Set<Long> used = new HashSet<>();
		for (JsonNode line : tail.path("lines")) {
			if (lines.size() >= MAX_LINES) {
				break;
			}
			String situation = cut(line.path("situation").asString("").strip(), MAX_SITUATION_CHARS);
			List<String> titles = new ArrayList<>();
			for (JsonNode no : line.path("nos")) {
				int n = no.asInt(0);
				if (n >= 1 && n <= ids.size()) {
					long bookId = ids.get(n - 1);
					if (shown.contains(bookId) && known.containsKey(bookId) && used.add(bookId)) {
						titles.add("『" + known.get(bookId).title() + "』");
					}
				}
			}
			if (!situation.isEmpty() && !titles.isEmpty()) {
				lines.add(situation + ": " + String.join(", ", titles));
			}
		}
		if (!lines.isEmpty()) {
			return String.join("\n", lines);
		}
		String summary = tail.path("summary").asString("").strip();
		if (summary.isEmpty() || summary.contains("『") || NUMBERS.matcher(summary).find()
				|| summary.codePointCount(0, summary.length()) > MAX_SUMMARY_CHARS) {
			return null;
		}
		return summary;
	}

	private static String cut(String text, int max) {
		return text.codePointCount(0, text.length()) <= max ? text : text.substring(0, text.offsetByCodePoints(0, max));
	}

}
