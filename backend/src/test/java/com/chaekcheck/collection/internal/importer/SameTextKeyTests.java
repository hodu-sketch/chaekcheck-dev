package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Step ④'s key (cycle 12 spec R2~R4, design-delta tests): the R3-4 examples, the leading series number, edition
 * words, the placeholder lists and the tails.
 */
class SameTextKeyTests {

	static final SameTextKey RULES = SameTextKey.RULES;

	static String key(String title) {
		return key(title, "홍길동 지음", "가나출판");
	}

	static String key(String title, String author, String publisher) {
		return RULES.of(title, author, publisher).key();
	}

	@Test
	void mergesTitlesThatDifferOnlyInTheSubtitle() {
		assertThat(key("백만장자 마인드")).isNotNull().isEqualTo(key("백만장자 마인드 - 부를 끌어 당기는"));
		assertThat(key("납치범들 : 콘스탄스 던랩")).isEqualTo(key("납치범들 - 콘스탄스 던랩"));
		assertThat(key("삼체 3부 - 사신의 영생")).isEqualTo(key("삼체 3부"));
	}

	@Test
	void keepsVolumesApart() {
		assertThat(key("오개념 탈출 프로젝트 - 수학 1")).isNotEqualTo(key("오개념 탈출 프로젝트 - 수학 2"));
		assertThat(key("묘왕동주 1 - 상")).isNotEqualTo(key("묘왕동주 1 - 하"));
		assertThat(key("묘왕동주 1 - 상")).isNotEqualTo(key("묘왕동주 1 - 중"));
		assertThat(key("이기적 유전자")).isNotEqualTo(key("이기적 유전자(40주년 기념판)"));
	}

	@Test
	void readsTheVolumeMarks() {
		assertThat(RULES.volumeMarks("수학 1")).isEqualTo("n=1|s=|e=");
		assertThat(RULES.volumeMarks("묘왕동주 1 - 상")).isEqualTo("n=1|s=상|e=");
		assertThat(RULES.volumeMarks("오키나와 셀프트래블(2018-2019)")).isEqualTo("n=2018,2019|s=|e=");
		assertThat(RULES.volumeMarks("이기적 유전자(40주년 기념판)")).isEqualTo("n=40|s=|e=기념판");
		assertThat(RULES.volumeMarks("토지 (상)")).isEqualTo("n=|s=상|e=");
		assertThat(RULES.volumeMarks("토지 下권")).isEqualTo("n=|s=下|e=");
		assertThat(RULES.volumeMarks("수학 1 개정 기념판 상")).isEqualTo("n=1|s=상|e=개정,기념판");
		assertThat(RULES.volumeMarks(null)).isEqualTo("n=|s=|e=");
	}

	@Test
	void doesNotTakeUpperMiddleLowerInsideAWord() {
		assertThat(RULES.volumeMarks("하늘과 바람과 별과 시")).isEqualTo("n=|s=|e=");
		assertThat(RULES.volumeMarks("세상 중심에서")).isEqualTo("n=|s=|e=");
		assertThat(RULES.volumeMarks("상하이 이야기")).isEqualTo("n=|s=|e=");
	}

	@Test
	void comparesNumbersAsIntegers() {
		assertThat(key("해리 포터 01")).isEqualTo(key("해리 포터 1"));
		assertThat(key("해리 포터 1")).isNotEqualTo(key("해리 포터 10"));
		assertThat(RULES.volumeMarks("0권")).isEqualTo("n=0|s=|e=");
		assertThat(RULES.volumeMarks("１２")).isEqualTo("n=12|s=|e=");
	}

	@Test
	void takesTheLongestEditionWordFirst() {
		assertThat(RULES.volumeMarks("민법 전면개정판")).isEqualTo("n=|s=|e=전면개정");
		assertThat(RULES.volumeMarks("민법 전면 개정판")).isEqualTo("n=|s=|e=전면개정");
		assertThat(key("민법 전면개정판")).isNotEqualTo(key("민법 개정판"));
		assertThat(RULES.volumeMarks("민법 개정판 (특별판)")).isEqualTo("n=|s=|e=개정,특별판");
	}

	@Test
	void leavesOutTheLeadingSeries() {
		assertThat(key("[100분 고전 013] 논어")).isEqualTo(key("논어"));
		assertThat(key("〈세계문학 3〉 햄릿")).isEqualTo(key("햄릿"));
	}

	@Test
	void hasNoKeyForPlaceholders() {
		assertThat(RULES.of("햄릿", "기타", "북큐브")).isEqualTo(new SameTextKey.Key(null, true));
		assertThat(RULES.of("햄릿", "Unknown", "북큐브")).isEqualTo(new SameTextKey.Key(null, true));
		assertThat(RULES.of("햄릿", "셰익스피어", "기타")).isEqualTo(new SameTextKey.Key(null, true));
		assertThat(RULES.of("햄릿", "셰익스피어", " 미 상 ")).isEqualTo(new SameTextKey.Key(null, true));
		assertThat(RULES.of("햄릿", "셰익스피어", "UNKNOWN")).isEqualTo(new SameTextKey.Key(null, true));
		assertThat(RULES.of("햄릿", "기타", "기타")).isEqualTo(new SameTextKey.Key(null, true));
	}

	@Test
	void hasNoKeyWhenAPartIsMissing() {
		assertThat(RULES.of("햄릿", "셰익스피어", ".")).isEqualTo(new SameTextKey.Key(null, false));
		assertThat(RULES.of("햄릿", ".", "북큐브")).isEqualTo(new SameTextKey.Key(null, false));
		assertThat(RULES.of("햄릿", null, "북큐브")).isEqualTo(new SameTextKey.Key(null, false));
		assertThat(RULES.of(null, "셰익스피어", "북큐브")).isEqualTo(new SameTextKey.Key(null, false));
		assertThat(RULES.of("햄릿", "셰익스피어", null)).isEqualTo(new SameTextKey.Key(null, false));
		// an editor is not an author (R4-2)
		assertThat(RULES.of("한국 명시선", "홍길동 편", "가나출판")).isEqualTo(new SameTextKey.Key(null, false));
		assertThat(RULES.of("한국 명시선", "홍길동 엮음", "가나출판").key()).isNull();
	}

	@Test
	void keepsNamesThatOnlyLookLikePlaceholders() {
		assertThat(key("춘향전", "작자미상", "가나출판")).isNotNull();
		assertThat(key("춘향전", "작자 미상", "가나출판")).isNotNull().isEqualTo(key("춘향전", "작자미상", "가나출판"));
		assertThat(key("춘향전", "작가미상", "가나출판")).isNotNull();
		assertThat(key("춘향전", "미상", "가나출판")).isNotNull();
		assertThat(key("춘향전", "편집부", "편집부")).isNotNull();
		assertThat(key("햄릿", "셰익스피어", "북큐브")).isNotNull();
	}

	@Test
	void takesTheNextAuthorAfterAPlaceholder() {
		assertThat(key("햄릿", "기타, 셰익스피어", "가나출판")).isNotNull().isEqualTo(key("햄릿", "셰익스피어", "가나출판"));
		assertThat(key("햄릿", "Unknown, 셰익스피어 저", "가나출판")).isEqualTo(key("햄릿", "셰익스피어", "가나출판"));
	}

	@Test
	void ignoresCaseAndSpacesOfPlaceholders() {
		assertThat(RULES.of("햄릿", "UNKNOWN", "가나출판").key()).isNull();
		assertThat(RULES.of("햄릿", "셰익스피어", "Unknown").key()).isNull();
		assertThat(RULES.of("햄릿", "셰익스피어", "기 타").key()).isNull();
	}

	@Test
	void cutsTheTails() {
		SameTextKey rules = new SameTextKey(List.of("(PC만 열람 가능)"));
		assertThat(rules.comparisonTitle("백만장자 마인드(PC만 열람 가능)")).isEqualTo("백만장자마인드");
		assertThat(rules.comparisonTitle("백만장자 마인드 (pc만 열람가능)")).isEqualTo("백만장자마인드");
		assertThat(rules.of("백만장자 마인드 (PC만 열람 가능)", "홍길동", "가나출판").key())
			.isEqualTo(rules.of("백만장자 마인드", "홍길동", "가나출판").key());
		assertThat(rules.volumeMarks("수학 1 (PC만 열람 가능)")).isEqualTo("n=1|s=|e=");
		// nothing left: the main title stays
		assertThat(rules.comparisonTitle("(PC만 열람 가능)")).isEqualTo("(pc만열람가능)");
		// without the tail list the tail stays in the title
		assertThat(RULES.comparisonTitle("백만장자 마인드(PC만 열람 가능)")).isEqualTo("백만장자마인드(pc만열람가능)");
	}

	@Test
	void joinsThePartsWithTheSeparator() {
		assertThat(key("수학 1", "홍길동", "(주)가나출판"))
			.isEqualTo("수학1" + BookGrouper.SEP + "n=1|s=|e=" + BookGrouper.SEP + "홍길동" + BookGrouper.SEP + "가나출판");
		assertThat(BookGrouper.sameTextKey("수학 1", "홍길동", "(주)가나출판").key()).isEqualTo(key("수학 1", "홍길동", "(주)가나출판"));
	}

}
