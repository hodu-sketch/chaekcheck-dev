package com.chaekcheck.aisearch.internal.firstcall;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The keywords of the ML first call: the question's own words (M, docs/aisearch/reports/2026-10-25-first-call-models.md
 * 5-4), split on spaces and punctuation, a common ending cut off, words of one letter and request words dropped, at
 * most 8; as {@code cands_e2e.words}.
 */
final class QuestionWords {

	static final int MAX = 8;

	private static final Pattern SPLIT = Pattern.compile("[\\s,.?!·~\"'()]+");

	private static final Pattern ENDING = Pattern
		.compile("(으로|에서|에게|한테|이랑|하고|까지|부터|처럼|같은|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$");

	private static final Set<String> STOP = Set.of("책", "도서", "관련", "사람", "처음", "위한", "대한", "읽는", "추천", "추천해줘",
			"추천해", "알려줘", "찾아줘", "읽을", "읽기", "좋은", "있어", "있나요", "있을까요", "싶어", "싶은", "거", "것", "걸", "같은",
			"비슷한", "처럼", "주세요", "해줘", "좀", "그냥", "말고", "빼고", "없는",
			// words that point at the dialogue, not a topic (plan 2026-10-29, D: 「아니아니 그거말고」 found books about speaking)
			"그거", "이거", "저거", "그런", "그게", "아니", "아니아니", "다른", "아까", "다시", "그거말고", "이거말고", "얘기", "이야기");

	private QuestionWords() {
	}

	static List<String> of(String question) {
		List<String> out = new ArrayList<>();
		for (String piece : SPLIT.split(question)) {
			String word = ENDING.matcher(piece).replaceFirst("");
			if (word.codePointCount(0, word.length()) >= 2 && !STOP.contains(word) && !out.contains(word)) {
				out.add(word);
			}
		}
		return out.size() > MAX ? List.copyOf(out.subList(0, MAX)) : List.copyOf(out);
	}

}
