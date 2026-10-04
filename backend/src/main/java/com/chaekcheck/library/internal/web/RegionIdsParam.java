package com.chaekcheck.library.internal.web;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Parses a comma-separated region id parameter ({@code residenceRegionIds}, {@code workRegionIds}) with the rules of
 * the search APIs: at most 20 ids per field, duplicates merged, missing or empty means no regions. The availability
 * module has its own copy; modules do not share internal classes.
 */
final class RegionIdsParam {

	static final int MAX_IDS = 20;

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private RegionIdsParam() {
	}

	/** @throws InvalidRegionIdsException when an id is not a positive integer or there are more than 20 */
	static Set<Long> parse(String field, String raw) {
		if (raw == null || raw.isBlank()) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidRegionIdsException(field, field + " must be comma-separated positive integers",
						"invalid_format");
			}
			ids.add(Long.parseLong(trimmed));
		}
		if (ids.size() > MAX_IDS) {
			throw new InvalidRegionIdsException(field, "At most " + MAX_IDS + " ids are allowed", "too_many");
		}
		return ids;
	}

	/** 400 validation_error; ids that parse but cannot be used are 422 invalid_region instead. */
	static final class InvalidRegionIdsException extends RuntimeException {

		final String field;

		final String code;

		InvalidRegionIdsException(String field, String message, String code) {
			super(message);
			this.field = field;
			this.code = code;
		}

	}

}
