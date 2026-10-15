package com.chaekcheck.catalog.normalize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/** Cycle 19 (ADR-0113): marks in author texts and role words stuck to the next name. */
class ContributorsTests {

	@Test
	void angleBracketsGo() { // R1-1
		assertThat(Contributors.parse("<김정은>,<김지혜> 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("김정은", Role.AUTHOR), tuple("김지혜", Role.AUTHOR));
		assertThat(Contributors.parse("<모리세 시게토모> 저/<이민연> 역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("모리세 시게토모", Role.AUTHOR), tuple("이민연", Role.TRANSLATOR));
	}

	@Test
	void unpairedAndFullWidthAngleBracketsGo() { // R1-1
		assertThat(Contributors.parse("＜율곡 이이,신사임당 저/이근오 편")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("율곡 이이", Role.AUTHOR), tuple("신사임당", Role.AUTHOR), tuple("이근오", Role.EDITOR));
		assertThat(Contributors.parse("＜이현욱＞역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("이현욱역", Role.UNKNOWN));
		assertThat(Contributors.stripMarks("＜이현욱＞역")).isEqualTo("이현욱역");
	}

	@Test
	void otherBracketMarksGoAndTextStays() { // R1-2
		assertThat(Contributors.parse("KBS 〈슈퍼차이나〉 제작팀")).extracting(Contributor::nameExact)
			.isEqualTo(Contributors.parse("KBS 슈퍼차이나 제작팀").stream().map(Contributor::nameExact).toList());
		assertThat(Contributors.stripMarks("KBS 〈슈퍼차이나〉 제작팀")).isEqualTo("KBS 슈퍼차이나 제작팀");
		assertThat(Contributors.stripMarks("《아사히 신문》경제부")).isEqualTo("아사히 신문경제부");
		assertThat(Contributors.stripMarks("「김영하」 지음")).isEqualTo("김영하 지음");
	}

	@Test
	void squareBracketsStay() { // R1-2
		assertThat(Contributors.stripMarks("존 버거 [지음]")).isEqualTo("존 버거 [지음]");
		assertThat(Contributors.parse("존 버거 [지음]")).extracting(Contributor::name).containsExactly("존 버거 [지음]");
	}

	@Test
	void onlyMarksIsNoPerson() { // 명세 결정 #7
		assertThat(Contributors.parse("<>")).isEmpty();
		assertThat(Contributors.firstNameExact("<>")).isNull();
		assertThat(Contributors.stripMarks("<>")).isEmpty();
		assertThat(Contributors.stripMarks(null)).isNull();
	}

	@Test
	void textWithoutMarksIsUnchanged() {
		assertThat(Contributors.stripMarks("  홍길동  저 ")).isEqualTo("  홍길동  저 ");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "<김정은> 저|김정은", "＜모리세 시게토모＞ 저/이민연 역|모리세시게토모",
			"루이스 캐럴 저이소연 역|루이스캐럴" })
	void firstNameExactUsesTheCleanName(String author, String name) { // sameText key and picks matching
		assertThat(Contributors.firstNameExact(author)).isEqualTo(name);
	}

	@Test
	void stuckRoleWordSplitsTwoPeople() { // R1-3, AC-7
		assertThat(Contributors.parse("루이스 캐럴 저이소연 역"))
			.extracting(Contributor::name, Contributor::role, Contributor::position)
			.containsExactly(tuple("루이스 캐럴", Role.AUTHOR, 0), tuple("이소연", Role.TRANSLATOR, 1));
		assertThat(Contributors.parse("가브리엘 뱅상 글이경혜 그림")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("가브리엘 뱅상", Role.AUTHOR), tuple("이경혜", Role.ILLUSTRATOR));
	}

	@Test
	void stuckRoleWordSplitsUpToThreePeople() { // R1-3, AC-7
		assertThat(Contributors.parse("마키타 겐지 저전선영 역강재헌 감수")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("마키타 겐지", Role.AUTHOR), tuple("전선영", Role.TRANSLATOR), tuple("강재헌", Role.OTHER));
		assertThat(Contributors.parse("안네마리 노르덴 저원유미 그림배정희 역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("안네마리 노르덴", Role.AUTHOR), tuple("원유미", Role.ILLUSTRATOR),
					tuple("배정희", Role.TRANSLATOR));
	}

	@Test
	void separateRoleWordBeforeKeepsTheName() { // R1-3 guard, AC-8
		assertThat(Contributors.parse("마르틴 발트샤이트 글/발터 글라소프 그림")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("마르틴 발트샤이트", Role.AUTHOR), tuple("발터 글라소프", Role.ILLUSTRATOR));
		assertThat(Contributors.parse("수잔 케인 지음, 다니엘라 저메인 역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("수잔 케인", Role.AUTHOR), tuple("다니엘라 저메인", Role.TRANSLATOR));
		assertThat(Contributors.parse("안나 저, 일리야 글라주노프 그림")).extracting(Contributor::name)
			.containsExactly("안나", "일리야 글라주노프");
		assertThat(Contributors.parse("김지음 지음/패트릭 글리슨 역")).extracting(Contributor::name)
			.containsExactly("김지음", "패트릭 글리슨");
	}

	@Test
	void familyNamePyeonIsNotARoleWord() { // research (c): 편 is not a first word
		assertThat(Contributors.parse("정이현 편혜영 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("정이현 편혜영", Role.AUTHOR));
	}

	@Test
	void displayTextSpacesStuckRoleWords() { // R1-5, AC-17
		assertThat(Contributors.displayText("밥 프록터 저이재경 역조성희 감수")).isEqualTo("밥 프록터 저 이재경 역 조성희 감수");
		assertThat(Contributors.displayText("루이스 캐럴 저이소연 역")).isEqualTo("루이스 캐럴 저 이소연 역");
		assertThat(Contributors.displayText("<김정은>,<김지혜> 저")).isEqualTo("김정은,김지혜 저");
		assertThat(Contributors.displayText("홍길동 저/<김정은> 저이소연 역")).isEqualTo("홍길동 저/김정은 저이소연 역");
	}

	@Test
	void displayTextKeepsGuardedAndPlainTexts() { // AC-17
		assertThat(Contributors.displayText("수잔 케인 지음, 다니엘라 저메인 역")).isEqualTo("수잔 케인 지음, 다니엘라 저메인 역");
		assertThat(Contributors.displayText("마르틴 발트샤이트 글/발터 글라소프 그림"))
			.isEqualTo("마르틴 발트샤이트 글/발터 글라소프 그림");
		assertThat(Contributors.displayText("정이현 편혜영 저")).isEqualTo("정이현 편혜영 저");
		assertThat(Contributors.displayText("존 버거 [지음]")).isEqualTo("존 버거 [지음]");
		assertThat(Contributors.displayText(null)).isNull();
	}

	@Test
	void displayTextOfADisplayTextIsTheSame() { // people are parsed from the raw text, never from the display text
		for (String raw : new String[] { "밥 프록터 저이재경 역조성희 감수", "루이스 캐럴 저이소연 역", "<김정은>,<김지혜> 저",
				"수잔 케인 지음, 다니엘라 저메인 역" }) {
			String display = Contributors.displayText(raw);
			assertThat(Contributors.displayText(display)).as(raw).isEqualTo(display);
		}
	}

}
