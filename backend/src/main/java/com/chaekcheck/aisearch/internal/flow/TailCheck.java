package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;
import com.chaekcheck.aisearch.internal.match.SearchText;

/**
 * The check of the summary by situation before it goes out (user 2026-10-29, production v1.21.0 and v1.21.1): the
 * model sometimes names the books by the list's numbers (「1, 16, 18번을 먼저 보시고」, half the summaries in a check of
 * six) or names a book that is not among the cards. Numbers of shown cards become their titles; a number of a book not
 * shown, or a 『title』 not among the cards, leaves the whole summary out.
 */
final class TailCheck {

	/** The list's numbers: 「3번」, 「1, 16, 18번」, 「2와 5번」; not 「두 번째」. */
	static final Pattern NUMBERS = Pattern.compile("(\\d+(?:\\s*(?:,|와|과|및|·|/)\\s*\\d+)*)\\s*번(?!째)");

	private static final Pattern NUMBER = Pattern.compile("\\d+");

	private static final Pattern TITLE = Pattern.compile("『([^』]+)』");

	/** Letters a 『title』 must keep to be matched to a card, at least. */
	private static final int MIN_TITLE_LETTERS = 2;

	private TailCheck() {
	}

	/**
	 * The summary to send, or null to leave it out.
	 * @param ids the candidates in the order the call saw them (number n is {@code ids.get(n - 1)})
	 * @param shown the books shown as cards
	 */
	static String clean(String summary, List<Long> ids, List<Long> shown, Map<Long, SearchBook> known) {
		StringBuilder out = new StringBuilder();
		Matcher numbers = NUMBERS.matcher(summary);
		int at = 0;
		while (numbers.find()) {
			List<String> titles = new ArrayList<>();
			Matcher number = NUMBER.matcher(numbers.group(1));
			while (number.find()) {
				int n = Integer.parseInt(number.group());
				if (n < 1 || n > ids.size() || !shown.contains(ids.get(n - 1)) || !known.containsKey(ids.get(n - 1))) {
					return null;
				}
				titles.add("『" + known.get(ids.get(n - 1)).title() + "』");
			}
			out.append(summary, at, numbers.start()).append(String.join(", ", titles));
			at = numbers.end();
		}
		String text = out.append(summary.substring(at)).toString();
		List<String> cardTitles = shown.stream()
			.filter(known::containsKey)
			.map(id -> SearchText.letters(known.get(id).title()))
			.toList();
		Matcher title = TITLE.matcher(text);
		while (title.find()) {
			String named = SearchText.letters(title.group(1));
			if (named.length() < MIN_TITLE_LETTERS
					|| cardTitles.stream().noneMatch(card -> card.startsWith(named) || named.startsWith(card))) {
				return null;
			}
		}
		return text;
	}

}
