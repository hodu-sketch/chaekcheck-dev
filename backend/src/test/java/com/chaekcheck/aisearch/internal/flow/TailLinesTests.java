package com.chaekcheck.aisearch.internal.flow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.match.SearchBooks.SearchBook;

/** The summary by situation from the pick call's numbers (2026-10-30: numbers in the cards' order dropped it). */
class TailLinesTests {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private static final List<Long> IDS = List.of(10L, 11L, 12L, 13L, 14L);

	private static final List<Long> SHOWN = List.of(12L, 14L, 10L);

	private static final Map<Long, SearchBook> KNOWN = Map.of(10L, new SearchBook(10, "가", "저자"), 12L,
			new SearchBook(12, "나", "저자"), 14L, new SearchBook(14, "다", "저자"));

	private static JsonNode tail(String json) {
		return JSON.readTree(json);
	}

	@Test
	void theListsNumbersGiveTheTitles() {
		assertThat(TailLines.render(tail("{\"lines\":[{\"situation\":\"처음\",\"nos\":[3,5]},{\"situation\":\"깊이\",\"nos\":[1]}],"
				+ "\"summary\":\"\"}"), IDS, SHOWN, KNOWN)).isEqualTo("처음: 『나』, 『다』\n깊이: 『가』");
	}

	@Test
	void numbersOfNoShownBookAreReadInTheCardsOrder() {
		// 2 and 4 are books not shown: 1, 2 and 3 are the first, second and third card
		assertThat(TailLines.render(tail("{\"lines\":[{\"situation\":\"처음\",\"nos\":[2]},{\"situation\":\"깊이\",\"nos\":[4]}],"
				+ "\"summary\":\"\"}"), IDS, SHOWN, KNOWN)).isEqualTo("처음: 『다』");
		assertThat(TailLines.whyNone(tail("{\"lines\":[{\"situation\":\"처음\",\"nos\":[4]}],\"summary\":\"\"}")))
			.isEqualTo("unshown 4");
	}

	@Test
	void aLineWithoutSituationOrBookIsNone() {
		assertThat(TailLines.render(tail("{\"lines\":[{\"situation\":\"\",\"nos\":[]}],\"summary\":\"\"}"), IDS, SHOWN,
				KNOWN)).isNull();
	}

}
