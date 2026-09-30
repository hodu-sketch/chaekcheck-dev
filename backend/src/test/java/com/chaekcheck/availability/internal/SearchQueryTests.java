package com.chaekcheck.availability.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class SearchQueryTests {

	@Test
	void trimsAndCollapsesWhitespace() {
		assertThat(SearchQuery.of("  채식\t\n  주의자  ").value()).isEqualTo("채식 주의자");
	}

	@Test
	void acceptsTwoToFiftyCharacters() {
		assertThat(SearchQuery.of("사랑").value()).isEqualTo("사랑");
		assertThat(SearchQuery.of("가".repeat(50)).value()).hasSize(50);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "   ", "가", " 가 " })
	void rejectsShorterThanTwo(String raw) {
		assertThatThrownBy(() -> SearchQuery.of(raw)).isInstanceOf(InvalidQueryException.class);
	}

	@Test
	void rejectsLongerThanFifty() {
		assertThatThrownBy(() -> SearchQuery.of("가".repeat(51))).isInstanceOf(InvalidQueryException.class);
	}

	@Test
	void countsCharactersNotUtf16Units() {
		assertThat(SearchQuery.of("😀".repeat(50)).value()).isNotEmpty();
	}

}
