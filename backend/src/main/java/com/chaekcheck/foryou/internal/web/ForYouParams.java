package com.chaekcheck.foryou.internal.web;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import com.chaekcheck.library.PlanOptions;

/**
 * The parameters of {@code /api/v1/for-you/*}. The plan parameters ({@code residenceRegionIds}, {@code workRegionIds},
 * {@code scope}, {@code visit}, {@code libraryIds}) have the names and rules of {@code /api/v1/picks/{listKey}} (copied
 * from picks' {@code PicksPlanParams}, which is internal to its module). Book ids: comma-separated positive integers,
 * each id once ({@code duplicate}), at most {@code max} ({@code too_many}).
 */
final class ForYouParams {

	static final int MAX_REGION_IDS = 20;

	static final int MAX_LIBRARY_IDS = 20;

	static final int MAX_BASE_BOOKS = 3;

	static final int MAX_RESHOW_BOOKS = 6;

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private final MultiValueMap<String, String> params;

	ForYouParams(MultiValueMap<String, String> params) {
		this.params = params;
	}

	PlanOptions planOptions() {
		String scope = single("scope");
		PlanOptions.Scope s = switch (scope == null ? "all" : scope.toLowerCase(Locale.ROOT)) {
			case "all" -> PlanOptions.Scope.ALL;
			case "joinable" -> PlanOptions.Scope.JOINABLE;
			default -> throw new InvalidForYouParamException("scope", "invalid_format", "scope must be all or joinable");
		};
		String visit = single("visit");
		PlanOptions.Visit v = switch (visit == null ? "any" : visit.toLowerCase(Locale.ROOT)) {
			case "any" -> PlanOptions.Visit.ANY;
			case "none" -> PlanOptions.Visit.NONE;
			default -> throw new InvalidForYouParamException("visit", "invalid_format", "visit must be any or none");
		};
		return new PlanOptions(s, v, ids("libraryIds", MAX_LIBRARY_IDS, false));
	}

	Set<Long> regionIds(String name) {
		return ids(name, MAX_REGION_IDS, false);
	}

	/** {@code baseBookIds}: 1 to 3, required. */
	List<Long> baseBookIds() {
		Set<Long> ids = ids("baseBookIds", MAX_BASE_BOOKS, true);
		if (ids.isEmpty()) {
			throw new InvalidForYouParamException("baseBookIds", "required", "baseBookIds is required");
		}
		return List.copyOf(ids);
	}

	/** {@code reshowBookIds}: 0 to 6. */
	List<Long> reshowBookIds() {
		return List.copyOf(ids("reshowBookIds", MAX_RESHOW_BOOKS, true));
	}

	private Set<Long> ids(String name, int max, boolean onceEach) {
		String raw = single(name);
		if (raw == null) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidForYouParamException(name, "invalid_format",
						name + " must be comma-separated positive integers");
			}
			if (!ids.add(Long.parseLong(trimmed)) && onceEach) {
				throw new InvalidForYouParamException(name, "duplicate", name + " has an id twice");
			}
		}
		if (ids.size() > max) {
			throw new InvalidForYouParamException(name, "too_many", "At most " + max + " ids are allowed");
		}
		return ids;
	}

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
			throw new InvalidForYouParamException(name, "duplicate", name + " may appear only once");
		}
		return given.isEmpty() ? null : given.getFirst();
	}

}
