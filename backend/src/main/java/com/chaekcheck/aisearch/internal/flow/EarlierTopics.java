package com.chaekcheck.aisearch.internal.flow;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The earlier question a follow-up continues from. Usually the last one; when the question points back at several
 * earlier questions (「위에 말한 두 개 섞은 느낌」, 「둘 다 들어간 거」, 「앞에서 물어본 것들 합쳐서」), those questions
 * joined, so the first step takes the words, shelves and relation from all of them (user 2026-10-26, 「앞 주제 둘
 * 합치기」). Words about the shown books (「두 권만」, 「두 번째 책」, 「둘 중에」) do not count.
 */
final class EarlierTopics {

	/** All earlier questions (as many as the history holds). */
	private static final Pattern ALL = Pattern.compile("모두|전부|것들|거 다|걸 다|다 합|다 섞");

	private static final Pattern THREE = Pattern.compile("세\\s*(개|가지|주제|분야|장르)|셋");

	private static final Pattern TWO = Pattern.compile(
			"두\\s*(개|가지|주제|분야|장르)(?!\\s*중)|둘\\s*(다|을|를|이|섞|합|같이|모두)|그\\s*둘|섞|합쳐|합친|합해|합한|겹치");

	private EarlierTopics() {
	}

	/**
	 * @param question this question
	 * @param earlier the earlier questions, oldest first (not empty)
	 */
	static String of(String question, List<String> earlier) {
		String last = earlier.getLast();
		if (earlier.size() < 2) {
			return last;
		}
		int n = ALL.matcher(question).find() ? earlier.size()
				: THREE.matcher(question).find() ? 3 : TWO.matcher(question).find() ? 2 : 1;
		return n == 1 ? last : String.join(" ", earlier.subList(Math.max(0, earlier.size() - n), earlier.size()));
	}

}
