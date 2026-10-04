package com.chaekcheck.availability.internal.web;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses {@code libraryIds}, the libraries the user added by hand (cycle 06): comma-separated positive integers,
 * duplicates merged, at most 20 after merging. Missing or empty means none. Unknown or disabled ids are not errors;
 * the plan ignores them (AC-10). Read after {@code visit} and before the regions.
 */
final class LibraryIdsParam {

	static final String FIELD = "libraryIds";

	static final int MAX_IDS = 20;

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private LibraryIdsParam() {
	}

	/** @throws InvalidParamException {@code invalid_format} or {@code too_many} */
	static Set<Long> parse(String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidParamException(FIELD, "invalid_format",
						FIELD + " must be comma-separated positive integers");
			}
			ids.add(Long.parseLong(trimmed));
		}
		if (ids.size() > MAX_IDS) {
			throw new InvalidParamException(FIELD, "too_many", "At most " + MAX_IDS + " ids are allowed");
		}
		return ids;
	}

}
