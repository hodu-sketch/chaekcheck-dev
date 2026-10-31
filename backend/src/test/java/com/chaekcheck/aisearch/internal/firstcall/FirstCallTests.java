package com.chaekcheck.aisearch.internal.firstcall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

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

	/**
	 * The profiles come only from {@code catalog.book_profile} (plan cycle 26 retro, user 2026-10-29 "지우기해줘"): with
	 * the table empty the ML first call is off and the searches ask Haiku, though the model and a {@code book-profiles.tsv}
	 * are in the folder; with rows it is on and reads them.
	 */
	@Test
	void theFirstCallIsOffWhileTheProfileTableIsEmpty() throws IOException {
		TinyFirstCall.write(dir.resolve(FirstCallModel.NAME));
		Files.writeString(dir.resolve("book-profiles.tsv"), "2\t과학\t\t\t\t\t\t\t\n");
		JsonMapper json = JsonMapper.builder().build();

		MlFirstCall empty = new MlFirstCall(properties(), json, profiles(java.util.Map.of()));
		MlFirstCall stored = new MlFirstCall(properties(), json, profiles(java.util.Map.of(2L,
				new com.chaekcheck.catalog.BookProfiles.Profile("과학", null, null, List.of(), null, null, null, List.of()))));

		assertThat(empty.on()).isFalse();
		assertThat(stored.on()).isTrue();
		assertThat(stored.plan("과학 책", TinyFirstCall.BOOK, null, null, null).route()).isEqualTo(MlFirstCall.Route.BOOK);
		assertThat(stored.shelfCounts(List.of(2L, 5L))).containsExactly(java.util.Map.entry("과학", 1));
	}

	@Test
	void withRulesOutOffNoBookIsRuledOut() throws IOException { // user 2026-10-31, step 6 report 3-4
		TinyFirstCall.write(dir.resolve(FirstCallModel.NAME));
		var novel = new com.chaekcheck.catalog.BookProfiles.Profile("소설", "", true, List.of(), null, null, null,
				List.of());
		Shelves notANovel = new Shelves(List.of(), List.of(), false, List.of(), "", "", "", List.of(), List.of(),
				List.of(), List.of());
		JsonMapper json = JsonMapper.builder().build();

		MlFirstCall on = new MlFirstCall(properties(true), json, profiles(java.util.Map.of(2L, novel)));
		MlFirstCall off = new MlFirstCall(properties(false), json, profiles(java.util.Map.of(2L, novel)));

		assertThat(on.rulesOut(2L, notANovel)).isTrue();
		assertThat(off.rulesOut(2L, notANovel)).isFalse();
	}

	private AiSearchProperties properties() {
		return properties(true);
	}

	private AiSearchProperties properties(boolean rulesOut) {
		return new AiSearchProperties(true, java.time.ZoneId.of("Asia/Seoul"), Duration.ofSeconds(5),
				Duration.ofSeconds(15), 512, 3072, 20, new AiSearchProperties.Rate(10, 100),
				new AiSearchProperties.Budget(java.math.BigDecimal.TWO),
				new AiSearchProperties.Price(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
				new AiSearchProperties.Limits(30, 8, 5, 200, 300),
				new AiSearchProperties.Vector(true, dir.toString(), 2, 50, 60, 60, Duration.ofSeconds(2),
						AiSearchProperties.Encoder.REMOTE, new AiSearchProperties.Remote("", "", Duration.ofSeconds(2)), 3,
						Duration.ofSeconds(30), new AiSearchProperties.Search(AiSearchProperties.SearchMode.EXACT, 10000, 2000)),
				new AiSearchProperties.FirstCall(AiSearchProperties.FirstCallMode.ML, 0.004, 512, null, rulesOut));
	}

	private static com.chaekcheck.catalog.BookProfiles profiles(
			java.util.Map<Long, com.chaekcheck.catalog.BookProfiles.Profile> rows) {
		return new com.chaekcheck.catalog.BookProfiles() {

			@Override
			public java.util.Map<Long, Profile> all() {
				return rows;
			}

			@Override
			public String version() {
				return String.valueOf(rows.size());
			}

		};
	}

	@Test
	void profilesFromTheTableMatchShelvesLikeTheFile() { // NULL cells of catalog.book_profile, 2026-10-29
		BookProfiles profiles = BookProfiles.of(java.util.Map.of(7L, new com.chaekcheck.catalog.BookProfiles.Profile(
				"자기계발", "공부법", false, List.of(), null, "교양", null, List.of())));
		Shelves asked = new Shelves(List.of("과학"), List.of("물리"), false, List.of(), "영미", "아이", "입문",
				List.of(), List.of(), List.of(), List.of());

		assertThat(profiles.of(7)).isEqualTo(new BookProfiles.Profile("자기계발", "공부법", false, List.of(), "", "교양",
				"", List.of()));
		// only "not a novel" is shared
		assertThat(asked.matches(profiles.of(7))).isEqualTo(1);
		assertThat(asked.excludes(profiles.of(7))).isFalse();
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
