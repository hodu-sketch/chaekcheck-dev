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
		assertThat(new SameTextKey(List.of()).comparisonTitle("백만장자 마인드(PC만 열람 가능)"))
			.isEqualTo("백만장자마인드(pc만열람가능)");
	}

	@Test
	void cutsTheSettledTails() {
		assertThat(RULES.comparisonTitle("백만장자 마인드(PC만 열람 가능)")).isEqualTo("백만장자마인드");
		assertThat(key("별을 헨다(잠 못 이루는 밤을 위해)")).isEqualTo(key("별을 헨다"));
		assertThat(key("아름다운 새벽 (한국인이 가장 좋아하는 단편소설)")).isEqualTo(key("아름다운 새벽"));
		assertThat(key("만화 문재인 - 운명을 바꾼 남자 (스마트 패드 전용) 1")).isEqualTo(key("만화 문재인 - 운명을 바꾼 남자 1"));
		// a trial is not a tail
		assertThat(key("퀀텀 독서법 (체험판)")).isNotEqualTo(key("퀀텀 독서법"));
	}

	@Test
	void takesOnlyVolumeNumbersFromALongSubtitle() {
		assertThat(key("결국 이기는 사람들의 비밀 - 불공평한 세상에서 발견한 10가지 성공 법칙"))
			.isEqualTo(key("결국 이기는 사람들의 비밀"));
		assertThat(key("백년을 살아보니 - 97세 철학자 김형석 교수의 인생론")).isEqualTo(key("백년을 살아보니"));
		assertThat(key("빅스페이서 매거진 : 부동산 분양 빅데이터 11월호 VOL. 1"))
			.isNotEqualTo(key("빅스페이서 매거진 : 부동산 분양 빅데이터 11월호 VOL. 2"));
		assertThat(key("홋카이도 셀프 트래블 - 2016-2017 최신판")).isNotEqualTo(key("홋카이도 셀프 트래블 - 2017~2018 최신판"));
		assertThat(key("홋카이도 셀프 트래블 - 2016-2017 최신판")).isNotEqualTo(key("홋카이도 셀프 트래블"));
		assertThat(SameTextKey.numbers("가 - 도시농부 올빼미의 텃밭가이드 2권")).containsExactly("2");
		assertThat(SameTextKey.numbers("가 - SciFan 제178권 시리즈의 한 권")).containsExactly("178");
		assertThat(SameTextKey.numbers("가 - 2014 커뮤니케이션이해총서 모음")).containsExactly("2014");
		// the number at the end of a long part (second dry run sample)
		assertThat(key("더 시커 : 마나가 필요 없어 01권")).isEqualTo(key("더 시커 : 마나가 필요 없어 01"));
		assertThat(key("미국인의 발견 - 살림지식총서 81")).isEqualTo(key("미국인의 발견 - 살림지식총서 081"));
		assertThat(SameTextKey.numbers("가 - 설계의 핵심 52")).containsExactly("52");
		// a short part gives all its numbers
		assertThat(SameTextKey.numbers("오개념 탈출 프로젝트 - 수학 1")).containsExactly("1");
		assertThat(SameTextKey.numbers("묘왕동주 1 - 상")).containsExactly("1");
	}

	@Test
	void readsRomanNumeralsInAVolumePlace() {
		assertThat(key("London and the Kingdom - Volume I")).isNotEqualTo(key("London and the Kingdom - Volume II"));
		assertThat(key("Fires - Book I")).isNotEqualTo(key("Fires - Book II"));
		assertThat(key("ALICE IN WONDERLAND - I")).isNotEqualTo(key("ALICE IN WONDERLAND - III"));
		assertThat(key("Mystr 럭키팩 7 - 스칼렛 핌퍼넬 I")).isNotEqualTo(key("Mystr 럭키팩 7 - 스칼렛 핌퍼넬 II"));
		// a long part: the numeral after "Vol." and the number at its end
		assertThat(SameTextKey.numbers("The Widow Barnaby - Vol. II (of 3)")).containsExactly("2", "3");
		assertThat(SameTextKey.numbers("x - A Journal of the Reigns of King George IV and King William IV, Vol. I"))
			.containsExactly("1");
		assertThat(RULES.volumeMarks("스칼렛 핌퍼넬 Ⅱ")).isEqualTo("n=2|s=|e=");
		assertThat(key("ENJOY 홍콩 Part2 지역여행Ⅱ")).isEqualTo(key("ENJOY 홍콩 Part 2 지역여행 Ⅱ"));
		assertThat(SameTextKey.romanValue("XIV")).isEqualTo(14);
		assertThat(SameTextKey.romanValue("XX")).isEqualTo(20);
		// not a numeral inside a word or in lower case
		assertThat(SameTextKey.numbers("VIVID - i love it")).isEmpty();
	}

	@Test
	void readsChineseNumeralsInAVolumePlace() {
		assertThat(key("오옥여 전집 - 作品卷一")).isNotEqualTo(key("오옥여 전집 - 作品卷三"));
		assertThat(SameTextKey.numbers("오옥여 전집 - 作品卷二")).containsExactly("2");
		assertThat(SameTextKey.numbers("三國志 第十二卷")).containsExactly("12");
		assertThat(SameTextKey.chineseValue("二十三")).isEqualTo(23);
		assertThat(SameTextKey.chineseValue("十")).isEqualTo(10);
		// inside a word it is no volume
		assertThat(SameTextKey.numbers("一人稱 소설")).isEmpty();
	}

	@Test
	void joinsThePartsWithTheSeparator() {
		assertThat(key("수학 1", "홍길동", "(주)가나출판"))
			.isEqualTo("수학1" + BookGrouper.SEP + "n=1|s=|e=" + BookGrouper.SEP + "홍길동" + BookGrouper.SEP + "가나출판");
		assertThat(BookGrouper.sameTextKey("수학 1", "홍길동", "(주)가나출판").key()).isEqualTo(key("수학 1", "홍길동", "(주)가나출판"));
	}

}
