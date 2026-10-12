package com.chaekcheck.picks.internal.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import com.chaekcheck.library.PlanOptions;

/**
 * The plan parameters of {@code /api/v1/picks}: {@code residenceRegionIds}, {@code workRegionIds},
 * {@code scope} ({@code all}|{@code joinable}), {@code visit} ({@code any}|{@code none}), {@code libraryIds}, with the
 * names and rules of {@code /api/v1/books} (catalog's {@code BookParams}, copied because it is internal to its module;
 * as in cycle 10): comma-separated positive integers, at most 20 per name after merging duplicates, each
 * single-valued name at most once ({@code duplicate}). Errors in the order scope, visit, libraryIds, then the regions
 * (400 {@code validation_error}); unknown region ids are 422 from the plan.
 */
final class PicksPlanParams {

	static final int MAX_REGION_IDS = 20;

	/** Libraries added by hand: at most 20 per account (cycle 06 spec R3). */
	static final int MAX_LIBRARY_IDS = 20;

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private final MultiValueMap<String, String> params;

	PicksPlanParams(MultiValueMap<String, String> params) {
		this.params = params;
	}

	/** Missing scope means all, missing visit means any. */
	PlanOptions planOptions() {
		String scope = single("scope");
		PlanOptions.Scope s = switch (scope == null ? "all" : scope.toLowerCase(Locale.ROOT)) {
			case "all" -> PlanOptions.Scope.ALL;
			case "joinable" -> PlanOptions.Scope.JOINABLE;
			default -> throw new InvalidPicksParamException("scope", "invalid_format", "scope must be all or joinable");
		};
		String visit = single("visit");
		PlanOptions.Visit v = switch (visit == null ? "any" : visit.toLowerCase(Locale.ROOT)) {
			case "any" -> PlanOptions.Visit.ANY;
			case "none" -> PlanOptions.Visit.NONE;
			default -> throw new InvalidPicksParamException("visit", "invalid_format", "visit must be any or none");
		};
		return new PlanOptions(s, v, ids("libraryIds", MAX_LIBRARY_IDS));
	}

	Set<Long> regionIds(String name) {
		return ids(name, MAX_REGION_IDS);
	}

	private Set<Long> ids(String name, int max) {
		String raw = single(name);
		if (raw == null) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidPicksParamException(name, "invalid_format",
						name + " must be comma-separated positive integers");
			}
			ids.add(Long.parseLong(trimmed));
		}
		if (ids.size() > max) {
			throw new InvalidPicksParamException(name, "too_many", "At most " + max + " ids are allowed");
		}
		return ids;
	}

	/** The one non-blank value of a name, or null. */
	private String single(String name) {
		List<String> values = params.get(name);
		if (values == null) {
			return null;
		}
		List<String> given = new ArrayList<>();
		for (String value : values) {
			if (value != null && !value.isBlank()) {
				given.add(value);
			}
		}
		if (given.size() > 1) {
			throw new InvalidPicksParamException(name, "duplicate", name + " may appear only once");
		}
		return given.isEmpty() ? null : given.getFirst();
	}

}
