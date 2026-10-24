package com.chaekcheck.aisearch.internal.match;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** {@link SearchText} (cycle 24 handoff step 4-2): NFKC, lower case, no spaces; book parts joined by one space. */
class SearchTextTests {

	@Test
	void foldsWidthCaseAndSpaces() {
		assertThat(SearchText.tidy("ＳＦ  단편　소설\tModern")).isEqualTo("sf단편소설modern");
		assertThat(SearchText.tidy("채식\u200B주의자\uFEFF")).isEqualTo("채식주의자");
		assertThat(SearchText.tidy(null)).isEmpty();
		assertThat(SearchText.tidy("   ")).isEmpty();
	}

	@Test
	void joinsTheBookPartsWithOneSpaceSoNoKeywordSpansTwo() {
		assertThat(SearchText.ofBook("코스모스 (개정판)", "칼 세이건 지음", "우주와 생명의 역사를 쉽게 풀어 쓴 교양서"))
			.isEqualTo("코스모스(개정판) 칼세이건지음 우주와생명의역사를쉽게풀어쓴교양서");
		assertThat(SearchText.ofBook("토지", null, null)).isEqualTo("토지");
		assertThat(SearchText.ofBook("토지", " ", "")).isEqualTo("토지");
		assertThat(SearchText.ofBook("소년이", "온다", null)).doesNotContain(SearchText.tidy("이 온"));
	}

}
