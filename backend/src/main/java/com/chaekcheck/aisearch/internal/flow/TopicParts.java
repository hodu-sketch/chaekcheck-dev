package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chaekcheck.aisearch.internal.firstcall.MlFirstCall;

/**
 * The topics a question lists side by side (「세계사나 과학사」, 「소설이랑 에세이」, 「요리, 여행」), each searched on its
 * own (user 2026-10-29 「A나 B는 주제마다 따로 찾기」: one search for 「세계역사나 과학사나」 filled the 30 candidates from
 * one side). Only 나·이나, 랑·이랑, 하고, 또는 and commas join topics here; 와·과 end too many words (결과, 사과).
 */
final class TopicParts {

	/** A word ending in a joining ending, the topic before it in group 1. */
	private static final Pattern JOINED = Pattern.compile("^(.{2,}?)(?:이나|이랑|하고|나|랑|,)$");

	private static final Pattern SPLIT = Pattern.compile("\\s+");

	/** The words that only join (「또는」, 「아니면」). */
	private static final Pattern OR = Pattern.compile("또는|아니면|혹은");

	/**
	 * Words that make a list of what stands before them (「요리 여행 캠핑 다 보여줘」, 「만화 웹툰 둘다」, 「정치 경제 사회
	 * 아무거나」, 「우주 아님 지구과학」, 「동양철학이든 서양철학이든」; user 2026-10-29, the rude test sentences).
	 */
	private static final Pattern LIST = Pattern
		.compile("둘\\s*다|셋\\s*다|섞어|아무거나|중에|이든|든$|아님|아니면|(^|\\s)다(\\s*(보여|줘|해)\\S*)?$");

	/** Words that are no topic in a list. */
	private static final Set<String> NOT_TOPIC = Set.of("아무거나", "아무거", "암거나", "암거", "하나", "하나만", "둘다", "셋다",
			"다", "걍", "쪽", "중에", "중에서", "섞어서", "섞어", "같이", "보여줘", "추천", "추천해줘", "이든", "아님", "아니면", "또는",
			"좀", "책", "둘", "셋", "그냥");

	private TopicParts() {
	}

	/**
	 * The topics the question lists, in order, each with what follows the list (「세계사나 과학사 입문서」 → 「세계사
	 * 입문서」, 「과학사 입문서」); a single topic when it lists none (two at least, each with a search word).
	 */
	static List<String> of(String question) {
		String[] words = SPLIT.split(question.strip().replace(",", ", ").replaceAll(" ,", ","));
		List<String> topics = new ArrayList<>();
		int i = 0;
		for (; i < words.length; i++) {
			String word = words[i];
			if (OR.matcher(word).matches()) {
				continue;
			}
			Matcher joined = JOINED.matcher(word);
			boolean nextJoins = i + 1 < words.length && OR.matcher(words[i + 1]).matches();
			if (joined.matches() && !MlFirstCall.firstKeywords(joined.group(1)).isEmpty()) {
				topics.add(joined.group(1));
			}
			else if (nextJoins && !MlFirstCall.firstKeywords(word).isEmpty()) {
				topics.add(word);
			}
			else if (!topics.isEmpty()) {
				break;
			}
		}
		if (topics.isEmpty() || topics.stream().anyMatch(NOT_TOPIC::contains)) {
			return listed(question);
		}
		// the word after the list is its last topic (「세계사나 과학사」), unless it says nothing (「세계사나 좀 더」)
		if (i < words.length && !MlFirstCall.firstKeywords(words[i]).isEmpty() && !words[i].endsWith("에서")) {
			topics.add(words[i]);
			i++;
		}
		if (topics.size() < 2) {
			return listed(question);
		}
		String rest = String.join(" ", List.of(words).subList(Math.min(i, words.length), words.length));
		return topics.stream().map(topic -> rest.isBlank() ? topic : topic + " " + rest).toList();
	}

	/** The topic words of a question with a list word, each a topic (two at least), else the question as one topic. */
	private static List<String> listed(String question) {
		if (!LIST.matcher(question.strip()).find()) {
			return List.of(question);
		}
		List<String> topics = new ArrayList<>();
		for (String word : SPLIT.split(question.strip())) {
			String w = word.replaceAll("(이든|든|이나|나|이랑|랑|하고|아님|,)$", "");
			if (!w.isEmpty() && !NOT_TOPIC.contains(w) && !LIST.matcher(w).matches() && !w.matches("[ㄱ-ㅎ]+")
					&& !MlFirstCall.firstKeywords(w).isEmpty()) {
				topics.add(w);
			}
		}
		return topics.size() >= 2 ? List.copyOf(topics) : List.of(question);
	}

}
