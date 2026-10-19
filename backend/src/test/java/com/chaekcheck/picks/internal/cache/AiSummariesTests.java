package com.chaekcheck.picks.internal.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class AiSummariesTests {

	private static AiSummaries of(String text) {
		return AiSummaries.load(new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));
	}

	@Test
	void findsARowByIsbnThenByListAndTitle() {
		AiSummaries summaries = of("""
				list\tisbn13\ttitle\tsummary
				librarian\t9788901276533\t가\t가 요약이에요.
				snu100\t\t논어\t공자의 가르침이에요.
				snu100\t\t빈 줄\t
				""");

		assertThat(summaries.of("leedongjin", "9788901276533", "다른 제목")).isEqualTo("가 요약이에요.");
		assertThat(summaries.of("snu100", null, "논어")).isEqualTo("공자의 가르침이에요.");
		assertThat(summaries.of("librarian", null, "논어")).isNull();
		assertThat(summaries.of("snu100", null, "빈 줄")).isNull();
	}

	@Test
	void theBuildCarriesTheFile() {
		assertThat(AiSummaries.DEFAULT.of("snu100", null, "논어")).isNotBlank();
	}

	@Test
	void noFileIsNoSummaries() {
		assertThat(AiSummaries.load(null).of("snu100", null, "논어")).isNull();
	}

}
