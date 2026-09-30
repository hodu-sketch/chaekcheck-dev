package com.chaekcheck.availability.internal.web;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses {@code candidates=libraryId:page,libraryId:page,..} of the next-batch stream (ADR-0014). Order is kept:
 * it is the request order. Every error is a 400 {@code validation_error} before the stream opens.
 */
final class CandidatesParam {

	static final String FIELD = "candidates";

	private static final Pattern ENTRY = Pattern.compile("([1-9][0-9]{0,17}):([1-9][0-9]{0,4})");

	private CandidatesParam() {
	}

	/** @param max most candidates one request may carry (K) */
	static List<Entry> parse(String raw, int max) {
		if (raw == null || raw.isBlank()) {
			throw new InvalidParamException(FIELD, "required", "candidates is required");
		}
		List<Entry> entries = new ArrayList<>();
		Set<Long> libraries = new HashSet<>();
		for (String part : raw.split(",", -1)) {
			Matcher m = ENTRY.matcher(part.strip());
			if (!m.matches()) {
				throw new InvalidParamException(FIELD, "invalid_format",
						"candidates must be comma-separated libraryId:page pairs");
			}
			long libraryId = Long.parseLong(m.group(1));
			if (!libraries.add(libraryId)) {
				throw new InvalidParamException(FIELD, "duplicate", "A library may appear only once");
			}
			entries.add(new Entry(libraryId, Integer.parseInt(m.group(2))));
		}
		if (entries.size() > max) {
			throw new InvalidParamException(FIELD, "too_many", "At most " + max + " candidates are allowed");
		}
		return entries;
	}

	record Entry(long libraryId, int page) {
	}

}
