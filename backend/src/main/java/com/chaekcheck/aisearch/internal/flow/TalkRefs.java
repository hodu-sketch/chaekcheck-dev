package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Words that point at the dialogue rather than name a book topic (plan decision 2026-10-29, A–E, user 「dev 24에 바로」):
 * 「모르겠어」 after an ask-back, 「그거 말고」, 「아까 이야기한 거」. Searching with these words found books about speaking
 * (「말」 of 「말고」) or the wrong topic; the search goes on from the topic they point at instead, and the pick call gets
 * a line saying what the user meant.
 */
final class TalkRefs {

	enum Kind {

		/** 「모르겠어」, 「아무거나」: the user cannot say which way. */
		DONT_KNOW,

		/** 「그거 말고」, 「둘 다 아니야」: not what was offered or shown. */
		NOT_THAT,

		/** 「아까 이야기한 거」, 「처음에 물어본 거」: back to an earlier topic. */
		BACK_TO

	}

	/**
	 * @param kind what the words point at
	 * @param rest the words left after the talk words (a topic the question names besides, as 「에세이」 in 「아까 그 에세이
	 *     얘기」), lower case
	 * @param first whether it points at the first topic (「처음」)
	 * @param that whether it points at something not shown (「저거」), so what is meant is not known
	 */
	record Ref(Kind kind, List<String> rest, boolean first, boolean that) {
	}

	private static final Pattern BACK = Pattern.compile(
			"(아까|처음|앞에서|앞서|전에|먼저)\\S*\\s*.*(말한|말했|이야기|얘기|물어본|물어봤|물었|그거|그 거|다시|거잖|건데)");

	private static final Pattern NOT_THAT = Pattern.compile("(그거|이거|저거|그것|이것|그런\\s*거|그런\\s*것|그게|그건|둘\\s*다)\\s*"
			+ "(말고|아니|아냐|아님)|말고\\s*다른|^\\s*(아니|아뇨|아니요)(\\s|$|아니)|다른\\s*거|딴\\s*거");

	private static final Pattern DONT_KNOW = Pattern
		.compile("모르겠|몰라|모름|아무거나|아무 거나|다 좋|둘 다 좋|글쎄|딱히|상관\\s*없|알아서|그냥\\s*추천|뭐든|아무래도 좋");

	private static final Pattern THAT = Pattern.compile("저거|저것|저 거");

	/**
	 * Talk words: a word of the question made of these pieces only (「그거말고」, 「이야기한거잖아」) is talk, not a topic.
	 */
	private static final List<String> TALK = List.of("아니아니", "아니요", "아뇨", "아니야", "아니고", "아니라", "아니", "아냐", "아님",
			"그거", "이거", "저거", "그것", "이것", "저것", "그런", "그게", "그건", "그", "이", "저", "거", "것", "말고", "다른", "딴",
			"아까전에", "아까", "전에", "처음에", "처음", "앞에서", "앞서", "먼저", "말한", "말했", "말했던", "이야기한", "이야기",
			"얘기한", "얘기", "물어본", "물어봤", "물었던", "다시", "보여줘", "보여", "줘", "잖아", "건데", "둘", "다", "모르겠어요",
			"모르겠어", "모르겠는데", "모르겠", "몰라", "아무거나", "아무", "좋아", "좋아요", "글쎄요", "글쎄", "딱히", "정한", "건",
			"없어", "상관없어", "상관", "알아서", "그냥", "추천해", "추천해줘", "추천", "해줘", "해", "뭐든", "나도", "잘", "요",
			"책", "한", "는", "은", "을", "를", "에", "의", "도", "야");

	/** Common endings taken off a topic word (「에세이를」 → 「에세이」). */
	private static final Pattern ENDING = Pattern.compile("(으로|에서|이랑|하고|이나|에|의|을|를|이|가|은|는|와|과|로|도|만|요)$");

	private TalkRefs() {
	}

	/** The words' kind, or null when the question names its own topic (or the talk words are not there). */
	static Ref of(String question) {
		String q = question.strip().toLowerCase(Locale.ROOT);
		Kind kind = BACK.matcher(q).find() ? Kind.BACK_TO
				: NOT_THAT.matcher(q).find() ? Kind.NOT_THAT : DONT_KNOW.matcher(q).find() ? Kind.DONT_KNOW : null;
		if (kind == null) {
			return null;
		}
		List<String> rest = rest(q);
		// 「그거 말고 에세이」 names a topic of its own: a normal question, the talk words left out of its search words
		if (kind == Kind.NOT_THAT && !rest.isEmpty()) {
			return null;
		}
		if (kind == Kind.DONT_KNOW && !rest.isEmpty()) {
			return null;
		}
		return new Ref(kind, rest, q.contains("처음"), THAT.matcher(q).find());
	}

	/** The words of the question that are not talk words, endings off, at least two letters each. */
	static List<String> rest(String question) {
		List<String> out = new ArrayList<>();
		for (String piece : question.toLowerCase(Locale.ROOT).split("[\\s,.?!·~\"'()]+")) {
			if (piece.isEmpty() || isTalk(piece)) {
				continue;
			}
			String word = ENDING.matcher(piece).replaceFirst("");
			if (word.codePointCount(0, word.length()) >= 2 && !isTalk(word)) {
				out.add(word);
			}
		}
		return out;
	}

	/** Whether the word is made of talk words only (split anywhere: 「그거말고」, 「이야기한거잖아」). */
	static boolean isTalk(String word) {
		boolean[] can = new boolean[word.length() + 1];
		can[0] = true;
		for (int end = 1; end <= word.length(); end++) {
			for (String talk : TALK) {
				int start = end - talk.length();
				if (start >= 0 && can[start] && word.startsWith(talk, start)) {
					can[end] = true;
					break;
				}
			}
		}
		return can[word.length()];
	}

	/**
	 * The topics of the dialogue, oldest first: each question that names a topic, with the answer to an ask-back joined
	 * to the asked question; questions made of talk words only are left out.
	 * @param questions the earlier questions, oldest first
	 * @param asked for each, whether that turn asked back
	 */
	static List<String> topics(List<String> questions, List<Boolean> asked) {
		List<String> topics = new ArrayList<>();
		for (int i = 0; i < questions.size(); i++) {
			String q = questions.get(i);
			Ref ref = of(q);
			if (ref != null) {
				// a turn that went back to an earlier topic makes that topic the current one again
				String back = ref.kind() == Kind.BACK_TO ? back(ref, topics) : null;
				if (back != null) {
					topics.remove(back);
					topics.add(back);
				}
				continue;
			}
			if (i > 0 && asked.get(i - 1) && !topics.isEmpty()) {
				topics.set(topics.size() - 1, topics.getLast() + " " + q);
			}
			else {
				topics.add(q);
			}
		}
		Map<String, String> distinct = new LinkedHashMap<>();
		for (String topic : topics.reversed()) {
			distinct.putIfAbsent(topic.strip(), topic.strip());
		}
		return List.copyOf(distinct.values()).reversed();
	}

	/**
	 * The earlier topic a 「아까 이야기한 거」 goes back to: the one holding a word it names (「아까 그 에세이 얘기」), the first
	 * for 「처음」, else the only one before the current topic; null when that is not known.
	 * @param topics the dialogue's topics so far, oldest first, the current one last
	 */
	static String back(Ref ref, List<String> topics) {
		for (String word : ref.rest()) {
			for (String topic : topics) {
				if (topic.toLowerCase(Locale.ROOT).contains(word)) {
					return topic;
				}
			}
		}
		if (ref.first() && !topics.isEmpty()) {
			return topics.getFirst();
		}
		return topics.size() == 2 ? topics.getFirst() : null;
	}

}
