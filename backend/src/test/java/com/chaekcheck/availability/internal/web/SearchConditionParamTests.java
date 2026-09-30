package com.chaekcheck.availability.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponentsBuilder;

import com.chaekcheck.availability.internal.InvalidQueryException;
import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.web.InvalidParamException.FieldError;

class SearchConditionParamTests {

	@ParameterizedTest
	@CsvSource({ "title=사랑, TITLE, 사랑", "author=김달, AUTHOR, 김달", "publisher=빅피시, PUBLISHER, 빅피시",
			"author=  김  달 , AUTHOR, 김 달" })
	void oneConditionGivesThatField(String query, SearchField field, String value) {
		assertThat(SearchConditionParam.parse(params(query))).isEqualTo(new SearchQuery(value, field));
	}

	@Test
	void otherParametersAreIgnored() {
		assertThat(SearchConditionParam.parse(params("filter=all&author=김달&scope=joinable")))
			.isEqualTo(SearchQuery.of("김달", SearchField.AUTHOR));
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "''", "author=", "author=%20%20", "q=사랑", "filter=all" })
	void noConditionIsRequired(String query) {
		assertThat(errors(query)).containsExactly(
				new FieldError("title", "required", "one of title, author, publisher is required"));
	}

	@Test
	void twoConditionsAreTooManyWithOneRowEach() {
		assertThat(errors("author=김달&title=사랑")).containsExactly(
				new FieldError("title", "too_many", "use only one of title, author, publisher"),
				new FieldError("author", "too_many", "use only one of title, author, publisher"));
		assertThat(errors("title=a&author=b&publisher=c")).extracting(FieldError::field)
			.containsExactly("title", "author", "publisher");
	}

	@Test
	void blankConditionNextToAnotherDoesNotCount() {
		assertThat(SearchConditionParam.parse(params("title=&author=김달")))
			.isEqualTo(SearchQuery.of("김달", SearchField.AUTHOR));
	}

	@Test
	void sameNameTwiceIsDuplicate() {
		assertThat(errors("author=a&author=b"))
			.containsExactly(new FieldError("author", "duplicate", "author may appear only once"));
	}

	@ParameterizedTest
	@CsvSource({ "title=가", "author=%20가%20" })
	void oneCharacterIsInvalidQuery(String query) {
		assertThatThrownBy(() -> SearchConditionParam.parse(params(query))).isInstanceOf(InvalidQueryException.class);
	}

	@Test
	void fiftyOneCharactersAreInvalidQuery() {
		assertThatThrownBy(() -> SearchConditionParam.parse(params("publisher=" + "가".repeat(51))))
			.isInstanceOf(InvalidQueryException.class);
		assertThat(SearchConditionParam.parse(params("publisher=" + "가".repeat(50))).value()).hasSize(50);
	}

	private static List<FieldError> errors(String query) {
		try {
			SearchConditionParam.parse(params(query));
		}
		catch (InvalidParamException ex) {
			return ex.errors;
		}
		throw new AssertionError("no InvalidParamException for " + query);
	}

	/** Decoded query parameters, as the servlet request gives them. */
	private static MultiValueMap<String, String> params(String query) {
		MultiValueMap<String, String> encoded = UriComponentsBuilder.fromUriString("/x?" + query)
			.build()
			.getQueryParams();
		MultiValueMap<String, String> decoded = new LinkedMultiValueMap<>();
		encoded.forEach((name, values) -> values
			.forEach(value -> decoded.add(name, value == null ? "" : URLDecoder.decode(value, StandardCharsets.UTF_8))));
		return decoded;
	}

}
