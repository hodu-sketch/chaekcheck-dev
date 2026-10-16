package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Cycle 21 handoff step 4-3 (ADR-0129, AC-7, AC-16): the inputs are our books' titles, as matched for the lists (plan
 * research {@code snu-100/picks.md} 3).
 */
class ShownTitleTests {

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			햄릿(Hamlet)                                           | 햄릿
			오디세이아(고대 그리스어 완역본)                       | 오디세이아
			토지. 1(1부 1권)                                       | 토지
			위대한 유산(상)                                        | 위대한 유산
			고향 (상)                                              | 고향
			백석의 사슴 오리지널판                                 | 백석의 사슴
			정지용 시집 향수 오리지널판                            | 정지용 시집 향수
			[100분 고전 031] 허생전ㆍ양반전ㆍ호질 - 박지원 대표 소설집 | 허생전ㆍ양반전ㆍ호질 - 박지원 대표 소설집
			당시삼백수. 1                                          | 당시삼백수
			삼국지 1권                                             | 삼국지
			토지. 2(1부 2권)                                       | 토지 2
			탁류 3                                                 | 탁류 3
			위대한 유산(하)                                        | 위대한 유산 하
			구운몽                                                 | 구운몽
			1984                                                   | 1984
			화씨 451                                               | 화씨 451
			미국의 민주주의 : 자유 너머의 인간                      | 미국의 민주주의 : 자유 너머의 인간
			""")
	void editionMarksAndTheFirstVolumeAreTakenOff(String title, String shown) {
		assertThat(ShownTitle.of(title)).isEqualTo(shown);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			(상)        | (상)
			[시리즈]    | [시리즈]
			오리지널판  | 오리지널판
			""")
	void nothingLeftKeepsTheTitle(String title, String shown) {
		assertThat(ShownTitle.of(title)).isEqualTo(shown);
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', textBlock = """
			토지. 1(1부 1권) | true
			위대한 유산(상)  | true
			구운몽           | true
			토지. 2(1부 2권) | false
			탁류 3           | false
			고향 (하)        | false
			삼국지 02권      | false
			""")
	void firstVolume(String title, boolean first) {
		assertThat(ShownTitle.read(title).firstVolume()).isEqualTo(first);
	}

}
