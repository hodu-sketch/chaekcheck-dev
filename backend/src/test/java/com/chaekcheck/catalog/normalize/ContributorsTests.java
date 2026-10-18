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
	void bracketedRoleWordIsTheRole() { // cycle 19 retro decision #5 (replaces R1-2 "[ ] stays")
		assertThat(Contributors.stripMarks("존 버거 [지음]")).isEqualTo("존 버거 지음");
		assertThat(Contributors.parse("존 버거 [지음]")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("존 버거", Role.AUTHOR));
		assertThat(Contributors.parse("금장태, 고광직[공저]")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("금장태", Role.AUTHOR), tuple("고광직", Role.AUTHOR));
		assertThat(Contributors.parse("송시열[저],서정기[역]")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("송시열", Role.AUTHOR), tuple("서정기", Role.TRANSLATOR));
	}

	@Test
	void loneRoleWordAfterBracketedRolesIsDropped() { // retro decision #5: "김삼웅 [편] 저" (193 of 231 texts)
		assertThat(Contributors.stripMarks("김삼웅 [편] 저")).isEqualTo("김삼웅 편");
		assertThat(Contributors.parse("김삼웅 [편] 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("김삼웅", Role.EDITOR));
		assertThat(Contributors.stripMarks("K. 만하임 [저] 임석진 [역] 저")).isEqualTo("K. 만하임 저, 임석진 역");
		assertThat(Contributors.parse("K. 만하임 [저] 임석진 [역] 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("K. 만하임", Role.AUTHOR), tuple("임석진", Role.TRANSLATOR));
		assertThat(Contributors.parse("이만수 [저] 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("이만수", Role.AUTHOR));
		// a role word with more after it is not the lone suffix
		assertThat(Contributors.stripMarks("홍길동 [저] 김철수 역")).isEqualTo("홍길동 저, 김철수 역");
	}

	@Test
	void bracketedRoleWordFollowedByANameSplitsThePeople() { // retro decision #5: "정도전[저]정병철[편]" (48 rows)
		assertThat(Contributors.stripMarks("정도전[저]정병철[편]")).isEqualTo("정도전 저, 정병철 편");
		assertThat(Contributors.parse("정도전[저]정병철[편]")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("정도전", Role.AUTHOR), tuple("정병철", Role.EDITOR));
		assertThat(Contributors.parse("누가 알렉산더 베로니스 [지음] 남정우 [편역]"))
			.extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("누가 알렉산더 베로니스", Role.AUTHOR), tuple("남정우", Role.EDITOR));
		assertThat(Contributors.parse("송복 [편] 조희연 [역]")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("송복", Role.EDITOR), tuple("조희연", Role.TRANSLATOR));
	}

	@Test
	void bracketedNameKeepsTheTextAndLosesTheBrackets() { // retro decision #5
		assertThat(Contributors.stripMarks("KBS [슈퍼차이나] 제작팀")).isEqualTo("KBS 슈퍼차이나 제작팀");
		assertThat(Contributors.parse("KBS [슈퍼차이나] 제작팀 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("KBS 슈퍼차이나 제작팀", Role.AUTHOR));
		assertThat(Contributors.parse("KBS [슈퍼차이나] 제작팀 저")).extracting(Contributor::nameExact)
			.isEqualTo(Contributors.parse("KBS 〈슈퍼차이나〉 제작팀 저").stream().map(Contributor::nameExact).toList());
		assertThat(Contributors.stripMarks("채만식[蔡萬植]")).isEqualTo("채만식蔡萬植");
		assertThat(Contributors.stripMarks("[트렌즈]지 특별취재팀")).isEqualTo("트렌즈지 특별취재팀");
		assertThat(Contributors.stripMarks("마이니치신문 [간병 살인] 취재반")).isEqualTo("마이니치신문 간병 살인 취재반");
		assertThat(Contributors.stripMarks("[Menon, ]")).isEqualTo("Menon,");
	}

	@Test
	void originalAuthorAndAnnotatingTranslatorAreRoleWords() { // plan approval 2026-10-18: 원저 → AUTHOR, 역주 → TRANSLATOR
		assertThat(Contributors.parse("이재선 역주")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("이재선", Role.TRANSLATOR));
		assertThat(Contributors.parse("R.M.스웨글러 원저 이기춘 역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("R.M.스웨글러 원저 이기춘", Role.TRANSLATOR)); // no comma: one item, as with other role words
		assertThat(Contributors.parse("R.M.스웨글러 원저, 이기춘 역")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("R.M.스웨글러", Role.AUTHOR), tuple("이기춘", Role.TRANSLATOR));
		assertThat(Contributors.stripMarks("R.M.스웨글러 [원저] 이기춘 [공역] 저")).isEqualTo("R.M.스웨글러 원저, 이기춘 공역");
		assertThat(Contributors.parse("R.M.스웨글러 [원저] 이기춘,여정성 [공역] 저"))
			.extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("R.M.스웨글러", Role.AUTHOR), tuple("이기춘", Role.TRANSLATOR),
					tuple("여정성", Role.TRANSLATOR));
		assertThat(Contributors.parse("장지연/신채호/박은식 [저] 이재선 [역주] 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("장지연", Role.UNKNOWN), tuple("신채호", Role.UNKNOWN), tuple("박은식", Role.AUTHOR),
					tuple("이재선", Role.TRANSLATOR)); // the "/" pieces stay UNKNOWN (backlog)
	}

	@Test
	void oldCatalogueNotesGo() { // plan approval 2026-10-18: pseud., from old catalog
		assertThat(Contributors.stripMarks("Victor [pseud.] Appleton 저")).isEqualTo("Victor Appleton 저");
		assertThat(Contributors.parse("Victor [pseud.] Appleton 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("Victor Appleton", Role.AUTHOR));
		assertThat(Contributors.stripMarks("[pseud.] Carey Rockwell 저")).isEqualTo("Carey Rockwell 저");
		assertThat(Contributors.stripMarks("Victor pseud. Appleton")).isEqualTo("Victor Appleton");
		assertThat(Contributors.stripMarks("Oliver [Pseud.] Onions")).isEqualTo("Oliver Onions");
		assertThat(Contributors.stripMarks("Abbot, Willis J[ohn], 1863-1934. [from old catalog]"))
			.isEqualTo("Abbot, Willis John, 1863-1934.");
		assertThat(Contributors.stripMarks("Beamer, Jacob, 1830- from old catalog")).isEqualTo("Beamer, Jacob, 1830-");
		assertThat(Contributors.stripMarks("Bavaria. Landesamt. [From Old Catalog] [1900]")).isEqualTo("Bavaria. Landesamt. 1900");
		assertThat(Contributors.stripMarks("[Cartier-Bresson, Mme. ] [from old catalog]")).isEqualTo("Cartier-Bresson, Mme.");
		assertThat(Contributors.stripMarks("Bache, Louise Franklin, [from old catalog] joint author"))
			.isEqualTo("Bache, Louise Franklin, joint author");
		// a note word inside another word, and other bracketed names, keep their text
		assertThat(Contributors.stripMarks("Pseudonymous Bosch")).isEqualTo("Pseudonymous Bosch");
		assertThat(Contributors.stripMarks("KBS [슈퍼차이나] 제작팀")).isEqualTo("KBS 슈퍼차이나 제작팀");
		assertThat(Contributors.stripMarks("채만식[蔡萬植]")).isEqualTo("채만식蔡萬植");
		assertThat(Contributors.firstNameExact("Victor [pseud.] Appleton 저")).isEqualTo("victorappleton");
	}

	@Test
	void oddSquareBracketsGo() { // retro decision #5: empty, inner spaces, unpaired, full width, only brackets
		assertThat(Contributors.stripMarks("홍길동 []")).isEqualTo("홍길동");
		assertThat(Contributors.stripMarks("존 버거 [ 지음 ]")).isEqualTo("존 버거 지음");
		assertThat(Contributors.stripMarks("존 버거 ［지음］")).isEqualTo("존 버거 지음");
		assertThat(Contributors.stripMarks("안중근 [安重根")).isEqualTo("안중근 安重根");
		assertThat(Contributors.parse("최재천 외 29명] 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("최재천", Role.AUTHOR));
		assertThat(Contributors.parse("<최재천 외 29명]> 저")).extracting(Contributor::name, Contributor::role)
			.containsExactly(tuple("최재천", Role.AUTHOR));
		assertThat(Contributors.parse("[]")).isEmpty();
		assertThat(Contributors.stripMarks("[]")).isEmpty();
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
			"루이스 캐럴 저이소연 역|루이스캐럴", "존 버거 [지음]|존버거", "KBS [슈퍼차이나] 제작팀 저|kbs슈퍼차이나제작팀",
			"정도전[저]정병철[편]|정도전" })
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
		assertThat(Contributors.displayText("존 버거 [지음]")).isEqualTo("존 버거 지음");
		assertThat(Contributors.displayText("정도전[저]정병철[편]")).isEqualTo("정도전 저, 정병철 편");
		assertThat(Contributors.displayText("KBS [슈퍼차이나] 제작팀 저")).isEqualTo("KBS 슈퍼차이나 제작팀 저");
		assertThat(Contributors.displayText(null)).isNull();
	}

	@Test
	void displayTextOfADisplayTextIsTheSame() { // people are parsed from the raw text, never from the display text
		for (String raw : new String[] { "밥 프록터 저이재경 역조성희 감수", "루이스 캐럴 저이소연 역", "<김정은>,<김지혜> 저",
				"수잔 케인 지음, 다니엘라 저메인 역", "정도전[저]정병철[편]", "K. 만하임 [저] 임석진 [역] 저" }) {
			String display = Contributors.displayText(raw);
			assertThat(Contributors.displayText(display)).as(raw).isEqualTo(display);
		}
	}

}
