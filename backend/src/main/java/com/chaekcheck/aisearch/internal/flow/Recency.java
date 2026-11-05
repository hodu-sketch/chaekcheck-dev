package com.chaekcheck.aisearch.internal.flow;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Whether a question asks for recent books (「최신」, 「요즘 나온」, 「신간」, 「2020년 이후」), and from which year (user
 * 2026-10-29 「최신을 후보 찾기 조건으로」: the year was shown to the second call only, so 30 candidates of mostly old books
 * left it nothing to pick, 「최신책 세계사」 production 20:55).
 */
final class Recency {

	/** How many years back 「최신」 and the like reach: this year and the {@value} before it (user 2026-10-29 「최근 3년」). */
	static final int YEARS = 3;

	private static final Pattern RECENT = Pattern.compile("최신|신간|신작|요즘|요새|최근|새로\\s*나온|갓\\s*나온|올해");

	/** 「2020년 이후」, 「2023년부터」, 「2022년 넘어서」, 「최소한 2023년에 나온」. */
	private static final Pattern SINCE = Pattern
		.compile("((?:19|20)\\d{2})\\s*년\\s*(?:이후|부터|넘어|뒤|이상|후)|최소\\S*\\s*((?:19|20)\\d{2})\\s*년");

	/**
	 * 「최소한 작년에 나온」, 「작년 이후」, 「재작년부터」, 「올해 나온」: a year counted from this one (user 2026-10-31: 「최소한
	 * 작년에 나온 JAVA 도서」 was not read as a year).
	 */
	private static final Pattern SINCE_RELATIVE = Pattern
		.compile("(올해|금년|재작년|작년|지난\\s*해)\\s*(?:에|에서)?\\s*(?:나온|출간|출판|발행|이후|부터|넘어|뒤|이상|후|신간|책|꺼|거)");

	/** A recency word as a keyword (「최신책」, 「요즘」): no book holds it as a topic, so it leaves the search words. */
	private static final Pattern WORD = Pattern
		.compile("^(?:최신|신간|신작|요즘|요새|최근|올해|금년|작년|재작년|최소)\\S*$|^\\d{4}년\\S*$");

	private Recency() {
	}

	/**
	 * The first year a recent book may have, from the first text that asks for one, or null when none does.
	 * @param texts the question first, then what it carries on (the topic it goes back to, earlier questions)
	 */
	static Integer from(List<String> texts, int thisYear) {
		for (String text : texts) {
			if (text == null) {
				continue;
			}
			Integer since = since(text, thisYear);
			if (since != null) {
				return since;
			}
			if (RECENT.matcher(text).find()) {
				return thisYear - YEARS;
			}
		}
		return null;
	}

	/**
	 * The year a text names as the earliest (「2020년 이후」, 「최소한 작년에 나온」), or null. Unlike 「최신」 it is a
	 * bound: older books are left out, not put after (user 2026-10-31).
	 */
	static Integer since(String text, int thisYear) {
		Matcher since = SINCE.matcher(text);
		if (since.find()) {
			int year = Integer.parseInt(since.group(1) != null ? since.group(1) : since.group(2));
			return year <= thisYear ? year : null;
		}
		Matcher relative = SINCE_RELATIVE.matcher(text);
		if (!relative.find()) {
			return null;
		}
		String word = relative.group(1).replaceAll("\s", "");
		return switch (word) {
			case "재작년" -> thisYear - 2;
			case "작년", "지난해" -> thisYear - 1;
			default -> thisYear;
		};
	}

	/**
	 * Whether a text has a recency word (「최신」, 「요즘」, 「최근」); whether it means newly published books is the
	 * question vector's call (「요즘 지쳤는데」, 「최근에 퇴사했는데」 are not, user 2026-10-29).
	 */
	static boolean mentions(String text) {
		return RECENT.matcher(text).find();
	}

	/** Whether a keyword only says 「recent」 (「최신책」, 「요즘」, 「2020년」). */
	static boolean isWord(String keyword) {
		return WORD.matcher(keyword).matches();
	}

}
