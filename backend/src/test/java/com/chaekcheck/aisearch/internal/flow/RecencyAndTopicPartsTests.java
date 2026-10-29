package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.chaekcheck.aisearch.internal.firstcall.MlFirstCall;

/**
 * 「최신」 as a search condition, topics listed side by side searched one by one, 「아까」 joining every topic it names and
 * 「다 같이」 (user 2026-10-29, the production dialogue of 20:54).
 */
class RecencyAndTopicPartsTests {

	@Test
	void recentWordsGiveTheLastThreeYearsAndASinceYearItsOwn() {
		assertThat(Recency.from(List.of("최신책"), 2026)).isEqualTo(2023);
		assertThat(Recency.from(List.of("요즘 나온 과학책"), 2026)).isEqualTo(2023);
		assertThat(Recency.from(List.of("2020년 이후 나온 소설"), 2026)).isEqualTo(2020);
		assertThat(Recency.from(List.of("세계사 입문서"), 2026)).isNull();
		// carried from the topic it goes on with
		assertThat(Recency.from(List.of("다 같이 하면 되자나", "최신책", "세계역사나 과학사나"), 2026)).isEqualTo(2023);
	}

	@Test
	void recencyWordsAreNoSearchWords() {
		assertThat(Recency.isWord("최신책")).isTrue();
		assertThat(Recency.isWord("요즘")).isTrue();
		assertThat(Recency.isWord("2023년")).isTrue();
		assertThat(Recency.isWord("세계사")).isFalse();
		assertThat(AiSearchFlow.onlyRecency("요새꺼")).isTrue();
		assertThat(AiSearchFlow.onlyRecency("최근꺼만")).isTrue();
		assertThat(AiSearchFlow.onlyRecency("최신 세계사")).isFalse();
	}

	@Test
	void topicsListedSideBySideAreSplitWithWhatFollows() {
		assertThat(TopicParts.of("세계사나 과학사 입문서")).containsExactly("세계사 입문서", "과학사 입문서");
		assertThat(TopicParts.of("이거말고 세계역사나 과학사나 좀 더 다양한 분야에서")).containsExactly(
				"세계역사 좀 더 다양한 분야에서", "과학사 좀 더 다양한 분야에서");
		assertThat(TopicParts.of("소설이랑 에세이")).containsExactly("소설", "에세이");
		assertThat(TopicParts.of("요리, 여행, 사진 책")).containsExactly("요리 책", "여행 책", "사진 책");
		// one topic stays whole
		assertThat(TopicParts.of("재밌고 의외인 것이 많은 교양서 알려줘")).containsExactly("재밌고 의외인 것이 많은 교양서 알려줘");
		assertThat(TopicParts.of("하나만 골라줘")).containsExactly("하나만 골라줘");
		// list words without joining endings (the rude test sentences)
		assertThat(TopicParts.of("정치 경제 사회 다")).containsExactly("정치", "경제", "사회");
		assertThat(TopicParts.of("우주 아님 지구과학")).containsExactly("우주", "지구과학");
		assertThat(TopicParts.of("조선사 고려사 둘다 걍")).containsExactly("조선사", "고려사");
		assertThat(TopicParts.of("아무거나 하나만 추천해줘")).containsExactly("아무거나 하나만 추천해줘");
	}

	@Test
	void orderWordsPickTheTopicsOfBackTo() {
		assertThat(TalkRefs.ordered("처음 거랑 끝에거", 3)).containsExactly(0, 2);
		assertThat(TalkRefs.ordered("앞에 둘", 3)).containsExactly(0, 1);
		assertThat(TalkRefs.ordered("전부 다 합쳐서", 3)).containsExactly(0, 1, 2);
		assertThat(TalkRefs.ordered("세계사 쪽", 3)).isEmpty();
		// a past form points back without 「아까」
		assertThat(TalkRefs.of("뇌과학 말한거")).isNotNull();
	}

	@Test
	void theJoiningEndingComesOffTheSearchWords() {
		assertThat(MlFirstCall.firstKeywords("세계역사나 과학사나")).containsExactly("세계역사", "과학사");
		assertThat(MlFirstCall.firstKeywords("사랑 하나")).containsExactly("사랑", "하나");
	}

	@Test
	void backToJoinsEveryTopicItNames() {
		List<String> topics = List.of("재밌고 의외인 것이 많은 교양서 알려줘", "이거말고 세계역사나 과학사나 좀 더 다양한 분야에서", "최신책");
		TalkRefs.Ref ref = TalkRefs.of("아니 최신책 아까 세계역사나 뭐 이런 것들 이야기했자나");
		assertThat(ref).isNotNull();
		assertThat(TalkRefs.back(ref, topics)).isEqualTo("이거말고 세계역사나 과학사나 좀 더 다양한 분야에서 최신책");
	}

	@Test
	void allTheChoicesAndAConditionAreTold() {
		assertThat(AiSearchFlow.ALL_CHOICES.matcher("다 같이 하면 되자나").find()).isTrue();
		assertThat(AiSearchFlow.ALL_CHOICES.matcher("전부").find()).isTrue();
		assertThat(AiSearchFlow.ALL_CHOICES.matcher("다").find()).isTrue();
		assertThat(AiSearchFlow.ALL_CHOICES.matcher("과학사 쪽으로").find()).isFalse();
		// every choice turned down is no 「다 같이」
		assertThat(AiSearchFlow.NOT_ALL.matcher("전부 별로야").find()).isTrue();
		assertThat(AiSearchFlow.CONDITION.matcher("최신책").find()).isTrue();
		assertThat(AiSearchFlow.CONDITION.matcher("짧은 거").find()).isTrue();
		assertThat(AiSearchFlow.CONDITION.matcher("요리").find()).isFalse();
	}

}
