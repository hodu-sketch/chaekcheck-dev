package com.chaekcheck.catalog.normalize;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/** Test vectors from research search-and-normalization 4장. */
class CatalogTextTests {

	@Test
	void publisherCompanyWordsGo() { // 4.3
		assertThat(Arrays.asList("(주) 도서출판 푸른숲", "(주)도서출판푸른숲", "푸른숲(주)", "푸른숲", "㈜푸른숲", "주식회사 푸른숲"))
			.allSatisfy(p -> assertThat(CatalogText.publisherNorm(p)).isEqualTo("푸른숲"));
		assertThat(CatalogText.publisherNorm("Penguin Books Co., Ltd.")).isEqualTo("penguinbooks");
		assertThat(CatalogText.publisherNorm("Cobalt Inc.")).isEqualTo("cobalt");
		assertThat(CatalogText.publisherNorm("교문사(청문각)")).isEqualTo("교문사(청문각)");
		assertThat(CatalogText.publisherNorm("  ")).isNull();
	}

	@Test
	void titleNormIsWholeTitleWithoutSpaces() { // 4.2
		assertThat(CatalogText.titleNorm("행동 경제학 : 인간 이해")).isEqualTo("행동경제학:인간이해");
		assertThat(CatalogText.titleNorm("Ⅱ ＡＢＣ")).isEqualTo("iiabc");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "[100분 고전 013] 논어|논어", "〈청소년 문학〉 소나기|소나기",
			"이기적 유전자(40주년 기념판)|이기적유전자(40주년기념판)", "행동 : 인간의 최선과 최악|행동", "코스모스 - 특별판|코스모스",
			"사피엔스:유인원에서|사피엔스:유인원에서", "[세트]|[세트]" })
	void mainTitleDropsSeriesAndSubtitle(String title, String main) { // 4.2
		assertThat(CatalogText.mainTitleNorm(title)).isEqualTo(main);
	}

	@Test
	void searchWordsAreSplitOnSpacesAndNormalized() {
		assertThat(CatalogText.words("  행동   경제학 ")).containsExactly("행동", "경제학");
		assertThat(CatalogText.words("ＡＢＣ")).containsExactly("abc");
		assertThat(CatalogText.words(" ")).isEmpty();
	}

	@Test
	void authorAndTranslator() { // A 저/B 역
		assertThat(Contributors.parse("로버트 M. 새폴스키 저/김명남 역"))
			.extracting(Contributor::name, Contributor::role, Contributor::nameExact, Contributor::position)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("로버트 M. 새폴스키", Role.AUTHOR, "로버트m새폴스키", 0),
					org.assertj.core.groups.Tuple.tuple("김명남", Role.TRANSLATOR, "김명남", 1));
	}

	@Test
	void aliasInBrackets() {
		assertThat(Contributors.parse("김도형(별별역사)")).singleElement().satisfies(c -> {
			assertThat(c.name()).isEqualTo("김도형");
			assertThat(c.alias()).isEqualTo("별별역사");
			assertThat(c.nameNorm()).isEqualTo("김도형별별역사");
			assertThat(c.role()).isEqualTo(Role.UNKNOWN);
		});
	}

	@Test
	void writerAndIllustratorGivesTwoRows() {
		assertThat(Contributors.parse("백희나 글그림")).extracting(Contributor::name, Contributor::role, Contributor::position)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("백희나", Role.AUTHOR, 0),
					org.assertj.core.groups.Tuple.tuple("백희나", Role.ILLUSTRATOR, 0));
	}

	@Test
	void hangulPartsSplitOnCommasAndOthersIsNotAPerson() {
		assertThat(Contributors.parse("홍길동, 김철수 공저 외")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("홍길동", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("김철수", Role.AUTHOR));
		assertThat(Contributors.parse("홍길동, 김철수 외 공저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("홍길동", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("김철수", Role.AUTHOR));
		assertThat(Contributors.parse("홍길동 외 2명")).extracting(Contributor::name).containsExactly("홍길동");
	}

	@Test
	void eachCommaItemCanCarryItsOwnRole() { // collected data sample 2026-09-30
		assertThat(Contributors.parse("그레타 가드 저자, 김현미 번역, 노고운 번역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("그레타 가드", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("김현미", Role.TRANSLATOR),
					org.assertj.core.groups.Tuple.tuple("노고운", Role.TRANSLATOR));
		assertThat(Contributors.parse("Ghat GPT 저자, sayne 역자")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("Ghat GPT", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("sayne", Role.TRANSLATOR));
		assertThat(Contributors.parse("윤동주 저/성낙수,전용근,김은정 공편")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("윤동주", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("성낙수", Role.EDITOR),
					org.assertj.core.groups.Tuple.tuple("전용근", Role.EDITOR),
					org.assertj.core.groups.Tuple.tuple("김은정", Role.EDITOR));
		assertThat(Contributors.parse("박현준, 박현성")).extracting(Contributor::role)
			.containsExactly(Role.UNKNOWN, Role.UNKNOWN);
	}

	@Test
	void organizationsStayOneName() { // 단체 이름은 저자로 둠 (research 4.1)
		assertThat(Contributors.parse("tvN STORY 〈벌거벗은 한국사〉 제작팀")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("tvN STORY 〈벌거벗은 한국사〉 제작팀", Role.UNKNOWN));
	}

	@Test
	void englishFamilyNameCommaStaysOnePerson() {
		assertThat(Contributors.parse("Richardson, James D.")).extracting(Contributor::name)
			.containsExactly("Richardson, James D.");
	}

	@Test
	void rolesInBracketsAndDictionary() {
		assertThat(Contributors.parse("김영하(지은이)/이우일 그림/박엮음 엮음/최감수 감수/정원작 (원작)"))
			.extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("김영하", Role.AUTHOR),
					org.assertj.core.groups.Tuple.tuple("이우일", Role.ILLUSTRATOR),
					org.assertj.core.groups.Tuple.tuple("박엮음", Role.EDITOR),
					org.assertj.core.groups.Tuple.tuple("최감수", Role.OTHER),
					org.assertj.core.groups.Tuple.tuple("정원작", Role.OTHER));
		assertThat(Contributors.parse("한글")).extracting(Contributor::name, Contributor::role)
			.containsExactly(org.assertj.core.groups.Tuple.tuple("한글", Role.UNKNOWN));
		assertThat(Contributors.parse(null)).isEmpty();
	}

	@Test
	void datesFutureAndImpossibleAreEmpty() { // 4.4
		LocalDate today = LocalDate.of(2026, 9, 30);
		assertThat(PublishedDates.parse("2023-04-18", today)).isEqualTo(LocalDate.of(2023, 4, 18));
		assertThat(PublishedDates.parse("20230418", today)).isEqualTo(LocalDate.of(2023, 4, 18));
		assertThat(PublishedDates.parse("2045-12-08", today)).isNull();
		assertThat(PublishedDates.parse("2045-07-00", today)).isNull();
		assertThat(PublishedDates.parse("2026-10-01", today)).isNull();
		assertThat(PublishedDates.parse("1917", today)).isNull();
		assertThat(PublishedDates.parse("1917-01-01", today)).isEqualTo(LocalDate.of(1917, 1, 1));
	}

	@Test
	void representativeDateIsMostCommonThenEarliest() {
		LocalDate a = LocalDate.of(2010, 11, 15), b = LocalDate.of(2012, 1, 13);
		assertThat(PublishedDates.representative(Arrays.asList(b, a, b, null))).isEqualTo(b);
		assertThat(PublishedDates.representative(Arrays.asList(b, a))).isEqualTo(a);
		assertThat(PublishedDates.representative(Arrays.asList(null, null))).isNull();
	}

	@Test
	void kyoboCodeToPaperIsbn() { // same-book-isbn 3절
		assertThat(Isbn.fromKyoboCode("4808954682152").isbn13()).isEqualTo("9788954682152");
		assertThat(Isbn.fromKyoboCode("4801175780096").isbn13()).isEqualTo("9791175780095");
		assertThat(Isbn.fromKyoboCode("4809012345678").result()).isIn(Isbn.KyoboResult.NOT_KOREAN,
				Isbn.KyoboResult.BAD_CHECK_DIGIT);
		assertThat(Isbn.fromKyoboCode("4808954682151").result()).isEqualTo(Isbn.KyoboResult.BAD_CHECK_DIGIT);
		assertThat(Isbn.fromKyoboCode("480D231030630").result()).isEqualTo(Isbn.KyoboResult.NOT_KYOBO_CODE);
	}

	@Test
	void nationalBibliographyIsbnText() { // same-book-isbn 4.1
		assertThat(Isbn.toIsbn13("89-20-90635-193350")).isEqualTo(Isbn.toIsbn13("8920906351"));
		assertThat(Isbn.toIsbn13("978-89-546-8215-2")).isEqualTo("9788954682152");
		assertThat(Isbn.toIsbn13("9788954682152 (93810)")).isEqualTo("9788954682152");
		assertThat(Isbn.toIsbn13("7500004087 (34)")).isNotNull().startsWith("978750000408");
		assertThat(Isbn.toIsbn13("abc")).isNull();
	}

}
