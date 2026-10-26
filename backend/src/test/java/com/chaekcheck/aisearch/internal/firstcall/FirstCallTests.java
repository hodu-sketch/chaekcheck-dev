package com.chaekcheck.aisearch.internal.firstcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

/** The ML first call's parts (user 2026-10-26): the model file, the shelves, the question's words, the plan. */
class FirstCallTests {

	@TempDir
	Path dir;

	@Test
	void theModelFileIsReadAndRunAsTheExportWroteIt() throws IOException {
		Path file = dir.resolve(FirstCallModel.NAME);
		// route head only: 2 dims, 2 hidden (identity), labels a and b; a wins for x, b for y
		String header = "{\"dim\":2,\"hidden\":2,\"heads\":[{\"name\":\"route\",\"kind\":\"route\",\"labels\":[\"a\",\"b\"],"
				+ "\"outputs\":2,\"tau\":0.9},{\"name\":\"field\",\"kind\":\"multi\",\"labels\":[\"과학\",\"소설\"],"
				+ "\"outputs\":2,\"threshold\":0.5,\"max\":2}]}";
		byte[] head = header.getBytes(StandardCharsets.UTF_8);
		float[][] blobs = { { 1, 0, 0, 1 }, { 0, 0 }, { 3, 0, 0, 3 }, { 0, 0 }, { 1, 0, 0, 1 }, { 0, 0 },
				{ 4, 0, 0, -4 }, { -1, -1 } };
		int floats = 0;
		for (float[] blob : blobs) {
			floats += blob.length;
		}
		ByteBuffer out = ByteBuffer.allocate(6 + 4 + head.length + floats * 4).order(ByteOrder.LITTLE_ENDIAN);
		out.put("CCFC1\n".getBytes(StandardCharsets.US_ASCII)).putInt(head.length).put(head);
		for (float[] blob : blobs) {
			for (float f : blob) {
				out.putFloat(f);
			}
		}
		Files.write(file, out.array());

		FirstCallModel model = FirstCallModel.read(file, JsonMapper.builder().build());

		// softmax(3, 0) = 0.95 for a: over tau 0.9
		assertThat(model.outputs("route", new float[] { 1, 0 })[0]).isCloseTo(Math.exp(3) / (Math.exp(3) + 1),
				org.assertj.core.data.Offset.offset(1e-6));
		assertThat(model.route(new float[] { 1, 0 })).isEqualTo("a");
		// softmax(1.5, 0) = 0.82 for a: under tau, so the last way
		assertThat(model.route(new float[] { 0.5f, 0 })).isEqualTo("b");
		// sigmoid(4 - 1) for 과학 over 0.5, sigmoid(-1) for 소설 under
		assertThat(model.labels("field", new float[] { 1, 0 })).containsExactly("과학");
		assertThatThrownBy(() -> model.outputs("route", new float[] { 1, 0, 0 }))
			.isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void aFileThatIsNotAModelIsRefused() throws IOException {
		Path file = dir.resolve(FirstCallModel.NAME);
		Files.writeString(file, "CCBV1\n....");
		assertThatThrownBy(() -> FirstCallModel.read(file, JsonMapper.builder().build())).isInstanceOf(IOException.class);
	}

	@Test
	void theProfilesFileIsReadLineByLine() throws IOException {
		Path file = dir.resolve(BookProfiles.NAME);
		Files.writeString(file, "7\t자기계발\t공부법\t0\t\t\t교양\t교양\t\n14\t소설\t\t1\t추리·스릴러|SF\t영미\t\t\t시리즈\n");

		BookProfiles profiles = BookProfiles.read(file);

		assertThat(profiles.count()).isEqualTo(2);
		assertThat(profiles.of(7)).isEqualTo(new BookProfiles.Profile("자기계발", "공부법", false, List.of(), "", "교양",
				"교양", List.of()));
		assertThat(profiles.of(14).genre()).containsExactly("추리·스릴러", "SF");
		assertThat(profiles.of(14).fiction()).isTrue();
		assertThat(profiles.of(99)).isNull();
	}

	@Test
	void theQuestionWordsDropEndingsAndRequestWords() { // as cands_e2e.words
		assertThat(QuestionWords.of("다이어트 식단을 자세히 다룬 책 추천해줘")).containsExactly("다이어트", "식단", "자세히", "다룬");
		// 되는 -> 되: one letter, dropped; 에세이 -> 에세 as measured (the keyword match takes it as part of a word)
		assertThat(QuestionWords.of("요즘 너무 지치고 힘들다, 위로가 되는 에세이?")).containsExactly("요즘", "너무", "지치고",
				"힘들다", "위로", "에세");
		assertThat(QuestionWords.of("a b c d e f g h i j kk ll mm nn oo pp qq rr ss tt")).hasSize(QuestionWords.MAX);
	}

	@Test
	void sharedShelvesCountAndAnExcludedShelfRulesABookOut() { // PFx
		Shelves asked = new Shelves(List.of("과학"), List.of("물리"), false, List.of(), "", "아이", "", List.of(),
				List.of(), List.of(), List.of("추리·스릴러"));
		BookProfiles.Profile physics = new BookProfiles.Profile("과학", "물리", false, List.of(), "", "아이", "입문", List.of());
		BookProfiles.Profile novel = new BookProfiles.Profile("소설", "", true, List.of("SF"), "", "", "", List.of());
		BookProfiles.Profile mystery = new BookProfiles.Profile("소설", "", null, List.of("추리·스릴러"), "", "", "", List.of());

		assertThat(asked.matches(physics)).isEqualTo(4);
		assertThat(asked.matches(null)).isZero();
		assertThat(asked.excludes(physics)).isFalse();
		// not a novel was asked for
		assertThat(asked.excludes(novel)).isTrue();
		assertThat(asked.excludes(mystery)).isTrue();
	}

	@Test
	void aFollowUpTakesTheEarlierShelvesUnderItsOwn() { // M4 dialogue state
		Shelves earlier = new Shelves(List.of("건강·의학"), List.of("다이어트·식단"), false, List.of(), "", "", "심화",
				List.of(), List.of(), List.of(), List.of());
		Shelves easier = new Shelves(List.of(), List.of(), null, List.of(), "", "", "입문", List.of(), List.of(), List.of(),
				List.of("추리·스릴러"));

		Shelves now = easier.over(earlier);

		assertThat(now.field()).containsExactly("건강·의학");
		assertThat(now.subfield()).containsExactly("다이어트·식단");
		assertThat(now.fiction()).isFalse();
		assertThat(now.level()).isEqualTo("입문");
		assertThat(now.excludeGenre()).containsExactly("추리·스릴러");
		assertThat(easier.newTopicAfter(earlier)).isFalse();
		Shelves mystery = new Shelves(List.of("소설"), List.of(), true, List.of(), "", "", "", List.of(), List.of(),
				List.of(), List.of());
		assertThat(mystery.newTopicAfter(earlier)).isTrue();
	}

	@Test
	void thePlanJoinsAFollowUpWithTheEarlierQuestionUnlessItIsANewTopic() {
		MlFirstCall ml = TinyFirstCall.create();

		MlFirstCall.Plan first = ml.plan("과학 책 추천", TinyFirstCall.BOOK, null, null, null);
		assertThat(first.route()).isEqualTo(MlFirstCall.Route.BOOK);
		assertThat(first.keywords()).containsExactly("과학");
		assertThat(first.shelves().field()).containsExactly("과학");
		assertThat(first.joinedVector()).isFalse();

		// the follow-up names no big shelf: it takes the earlier question's words, vector and shelves
		MlFirstCall.Plan easier = ml.plan("더 쉬운 걸로", TinyFirstCall.SITE, "우주 과학 책", TinyFirstCall.BOOK, null);
		assertThat(easier.route()).isEqualTo(MlFirstCall.Route.SITE);
		assertThat(easier.keywords()).containsExactly("쉬운", "우주", "과학");
		assertThat(easier.joinedVector()).isTrue();
		assertThat(easier.shelves().field()).containsExactly("과학");

		assertThat(ml.plan("오늘 점심 뭐 먹지", TinyFirstCall.NOT_BOOK, null, null, null).route())
			.isEqualTo(MlFirstCall.Route.NOT_BOOK);
		assertThat(ml.bonus(2, first.shelves())).isEqualTo(0.004);
		assertThat(ml.bonus(5, first.shelves())).isZero();
		assertThat(MlFirstCall.off().on()).isFalse();
	}

}
