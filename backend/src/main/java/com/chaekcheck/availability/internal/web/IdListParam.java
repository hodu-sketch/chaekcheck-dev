package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a comma-separated id list ({@code bookIds}, {@code candidates} of the holding streams). Order is kept. Every
 * error is a 400 {@code validation_error} before the stream opens: {@code required}, {@code invalid_format},
 * {@code duplicate}, {@code too_many}.
 */
final class IdListParam {

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private IdListParam() {
	}

	static List<Long> parse(String field, String raw, int max) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidParamException(field, "required", field + " is required");
		}
		List<Long> ids = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidParamException(field, "invalid_format", field + " must be comma-separated positive integers");
			}
			long id = Long.parseLong(trimmed);
			if (!seen.add(id)) {
				throw new InvalidParamException(field, "duplicate", "An id may appear only once");
			}
			ids.add(id);
		}
		if (ids.size() > max) {
			throw new InvalidParamException(field, "too_many", "At most " + max + " ids are allowed");
		}
		return ids;
	}

}
