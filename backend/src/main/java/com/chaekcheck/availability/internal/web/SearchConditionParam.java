package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.util.MultiValueMap;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;

/**
 * Reads the one search condition of the three search APIs (ADR-0025, design-delta "조건 파라미터 규칙"):
 * exactly one of {@code title}, {@code author}, {@code publisher}. {@code q} is not read. Values that are blank
 * count as missing. Errors are 400 {@code validation_error}; a value that is not 2 to 50 characters after
 * normalizing is 400 {@code invalid_query} as before.
 * <p>
 * Read from all query values, not {@code @RequestParam String}: Spring joins repeated values with commas, which
 * would hide {@code ?author=a&author=b}.
 */
final class SearchConditionParam {

	private static final String TOO_MANY = "use only one of title, author, publisher";

	private SearchConditionParam() {
	}

	/**
	 * @throws InvalidParamException with {@code required} (none), {@code too_many} (one row per given name) or
	 * {@code duplicate} (one name given twice)
	 * @throws com.chaekcheck.availability.internal.InvalidQueryException if the value is not 2 to 50 characters
	 */
	static SearchQuery parse(MultiValueMap<String, String> params) {
		List<SearchField> given = new ArrayList<>();
		for (SearchField field : SearchField.values()) {
			if (!values(params, field).isEmpty()) {
				given.add(field);
			}
		}
		if (given.isEmpty()) {
			throw new InvalidParamException(name(SearchField.TITLE), "required",
					"one of title, author, publisher is required");
		}
		if (given.size() > 1) {
			throw new InvalidParamException(given.stream()
				.map(field -> new InvalidParamException.FieldError(name(field), "too_many", TOO_MANY))
				.toList());
		}
		SearchField field = given.getFirst();
		List<String> values = values(params, field);
		if (values.size() > 1) {
			throw new InvalidParamException(name(field), "duplicate", name(field) + " may appear only once");
		}
		return SearchQuery.of(values.getFirst(), field);
	}

	/** Request parameter name of a field: {@code title}, {@code author}, {@code publisher}. */
	static String name(SearchField field) {
		return field.name().toLowerCase(Locale.ROOT);
	}

	private static List<String> values(MultiValueMap<String, String> params, SearchField field) {
		List<String> values = params.get(name(field));
		return values == null ? List.of() : values.stream().filter(value -> value != null && !value.isBlank()).toList();
	}

}
