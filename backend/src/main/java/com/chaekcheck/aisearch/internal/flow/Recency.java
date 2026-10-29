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

	/** 「2020년 이후」, 「2023년부터」, 「2022년 넘어서」. */
	private static final Pattern SINCE = Pattern.compile("((?:19|20)\\d{2})\\s*년\\s*(?:이후|부터|넘어|뒤|이상|후)");

	/** A recency word as a keyword (「최신책」, 「요즘」): no book holds it as a topic, so it leaves the search words. */
	private static final Pattern WORD = Pattern.compile("^(?:최신|신간|신작|요즘|요새|최근|올해)\\S*$|^\\d{4}년\\S*$");

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
			Matcher since = SINCE.matcher(text);
			if (since.find()) {
				int year = Integer.parseInt(since.group(1));
				return year <= thisYear ? year : null;
			}
			if (RECENT.matcher(text).find()) {
				return thisYear - YEARS;
			}
		}
		return null;
	}

	/** The year a text names (「2020년 이후」), or null. */
	static Integer since(String text, int thisYear) {
		Matcher since = SINCE.matcher(text);
		if (!since.find()) {
			return null;
		}
		int year = Integer.parseInt(since.group(1));
		return year <= thisYear ? year : null;
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
