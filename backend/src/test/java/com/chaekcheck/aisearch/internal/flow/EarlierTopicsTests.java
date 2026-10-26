package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class EarlierTopicsTests {

	private static final List<String> THREE_TURNS = List.of("판타지 소설", "추리 소설");

	@Test
	void pointingBackAtTwoTopicsJoinsTheLastTwoQuestions() { // user 2026-10-26
		for (String q : List.of("위에 말한 두 개 섞은 느낌", "둘 다 들어간 거", "앞에 두 가지 다 다룬 책", "그 둘 섞어서",
				"판타지랑 추리 섞인 거", "두 분야 겹치는 책")) {
			assertThat(EarlierTopics.of(q, THREE_TURNS)).as(q).isEqualTo("판타지 소설 추리 소설");
		}
		assertThat(EarlierTopics.of("앞에서 물어본 것들 합쳐서", List.of("과학", "판타지 소설", "추리 소설")))
			.isEqualTo("과학 판타지 소설 추리 소설");
	}

	@Test
	void wordsAboutTheShownBooksKeepTheLastQuestion() {
		for (String q : List.of("두 권만 추천해줘", "두 번째 책 같은 거 더", "둘 중에 쉬운 거", "두 개 중에 하나만", "좀 더 쉬운 걸로")) {
			assertThat(EarlierTopics.of(q, THREE_TURNS)).as(q).isEqualTo("추리 소설");
		}
		// one earlier turn: always that one
		assertThat(EarlierTopics.of("둘 다 들어간 거", List.of("추리 소설"))).isEqualTo("추리 소설");
	}

}
