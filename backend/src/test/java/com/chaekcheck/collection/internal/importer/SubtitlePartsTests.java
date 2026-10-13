package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The split of one same-text key by subtitle (cycle 12, user decision 2026-10-14): different subtitles are different
 * books only where one library holds two of them.
 */
class SubtitlePartsTests {

	static long[] libs(long... ids) {
		return ids;
	}

	@Test
	void splitsSubtitlesOneLibraryHoldsSideBySide() {
		// 고등편, 중등편, 초등편 all in library 7; the title without a subtitle stays apart
		assertThat(SubtitleParts.of(List.of("고등편", "중등편", "초등편", ""), List.of(libs(7, 8), libs(7), libs(7, 9), libs(8))))
			.containsExactly(1, 2, 3, SubtitleParts.APART);
	}

	@Test
	void keepsSubtitlesNoLibraryHoldsSideBySide() {
		assertThat(SubtitleParts.of(List.of("이슬아에세이", "양장", ""), List.of(libs(1), libs(2), libs(1, 3))))
			.containsExactly(SubtitleParts.WHOLE, SubtitleParts.WHOLE, SubtitleParts.WHOLE);
	}

	@Test
	void takesAContainedSubtitleAsTheSame() {
		// a shortened subtitle in the same library is the same subtitle, not a second book
		assertThat(SubtitleParts.of(List.of("치매암우울증", "치매암우울증쓸모많은뇌과학11", ""), List.of(libs(1), libs(1), libs(2))))
			.containsExactly(SubtitleParts.WHOLE, SubtitleParts.WHOLE, SubtitleParts.WHOLE);
	}

	@Test
	void joinsTheFirstMergeWithoutACommonLibrary() {
		// A and B share library 1; C shares none with A, so it joins A's merge
		assertThat(SubtitleParts.of(List.of("가", "나", "다"), List.of(libs(1), libs(1), libs(2))))
			.containsExactly(1, 2, 1);
		// C shares library 2 with A, none with B: it joins B
		assertThat(SubtitleParts.of(List.of("가", "나", "다"), List.of(libs(1, 2), libs(1), libs(2))))
			.containsExactly(1, 2, 2);
	}

	@Test
	void leavesOneSubtitleWhole() {
		assertThat(SubtitleParts.of(List.of("부를끌어당기는", ""), List.of(libs(1), libs(1))))
			.containsExactly(SubtitleParts.WHOLE, SubtitleParts.WHOLE);
	}

}
