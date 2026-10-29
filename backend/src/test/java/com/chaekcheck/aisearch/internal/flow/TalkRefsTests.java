package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.aisearch.internal.flow.TalkRefs.Kind;

class TalkRefsTests {

	@Test
	void notKnowingWhichWay() {
		for (String q : List.of("나도 잘 모르겠어", "잘 모르겠어요", "아무거나", "글쎄요", "다 좋아", "딱히 정한 건 없어", "그냥 추천해 줘", "모르겠어")) {
			assertThat(TalkRefs.of(q)).as(q).isNotNull().extracting(TalkRefs.Ref::kind).isEqualTo(Kind.DONT_KNOW);
		}
	}

	@Test
	void notThat() {
		for (String q : List.of("아 그거말고", "아니아니 그거말고", "그거 말고", "둘 다 아니야", "그런 거 말고", "아니 다른 거", "이거 말고",
				"그거 말고 다른 거 보여줘", "아니 그런 거 말고", "아니 그거 아니고", "그게 아니라", "말고 다른 거")) {
			assertThat(TalkRefs.of(q)).as(q).isNotNull().extracting(TalkRefs.Ref::kind).isEqualTo(Kind.NOT_THAT);
		}
		assertThat(TalkRefs.of("그거 말고 저거").that()).isTrue();
	}

	@Test
	void backToAnEarlierTopic() {
		for (String q : List.of("아까전에 이야기한거잖아", "아까 이야기한 거", "처음에 물어본 거 다시", "아까 그 에세이 얘기", "앞에서 말한 거 다시 보여줘",
				"아니 그거 말고 아까 그거")) {
			assertThat(TalkRefs.of(q)).as(q).isNotNull().extracting(TalkRefs.Ref::kind).isEqualTo(Kind.BACK_TO);
		}
		// the ending 「이」 comes off as in QuestionWords; 「에세」 is still found in the topic 「…에세이」
		assertThat(TalkRefs.of("아까 그 에세이 얘기").rest()).containsExactly("에세");
		assertThat(TalkRefs.of("처음에 물어본 거 다시").first()).isTrue();
	}

	@Test
	void aQuestionNamingItsOwnTopicIsNotTalk() {
		for (String q : List.of("추리 소설", "처음 읽는 경제 교양서", "그거 말고 에세이", "다이어트", "아니 에르노 소설", "모르는 사람과 대화하는 법",
				"이야기가 재미있는 소설")) {
			assertThat(TalkRefs.of(q)).as(q).isNull();
		}
	}

	@Test
	void talkWordsAreWholeWordsOnly() {
		assertThat(TalkRefs.isTalk("그거말고")).isTrue();
		assertThat(TalkRefs.isTalk("이야기한거잖아")).isTrue();
		assertThat(TalkRefs.isTalk("다이어트")).isFalse();
		assertThat(TalkRefs.rest("아까 다이어트 얘기")).containsExactly("다이어트");
	}

	@Test
	void theTopicsJoinAnAnswerToItsAskBackAndSkipTalk() {
		assertThat(TalkRefs.topics(List.of("AI 관련 기초 도서", "사진", "나도 잘 모르겠어", "아 그거말고"),
				List.of(false, true, false, false)))
			.containsExactly("AI 관련 기초 도서", "사진");
		assertThat(TalkRefs.topics(List.of("그림", "인상주의"), List.of(true, false))).containsExactly("그림 인상주의");
		// a turn that went back makes the earlier topic current again (the original dialogue, last turn)
		assertThat(TalkRefs.topics(List.of("AI 관련 기초 도서", "사진", "나도 잘 모르겠어", "아 그거말고", "아까전에 이야기한거잖아"),
				List.of(false, true, false, false, false)))
			.containsExactly("사진", "AI 관련 기초 도서");
	}

}
