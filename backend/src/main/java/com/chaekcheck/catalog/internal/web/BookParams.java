package com.chaekcheck.catalog.internal.web;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.util.MultiValueMap;

import com.chaekcheck.catalog.normalize.Contributors.Role;
import com.chaekcheck.catalog.internal.search.BookQuery;
import com.chaekcheck.library.PlanOptions;

/**
 * Reads the {@code /books} parameters (design-delta API, spec 2장) in the order conditions, {@code exact}·{@code roles}
 * ·{@code sort}, {@code scope}·{@code visit}·{@code libraryIds}, {@code page}·{@code pageSize}, regions [안]. Every single-valued name may
 * appear once ({@code duplicate}). Read from all query values: Spring would join repeats with commas.
 * <p>
 * {@code exact} for an empty field and {@code roles} without an author are ignored (dev decision, reported).
 */
final class BookParams {

	static final int MIN_LENGTH = 2;

	static final int MAX_LENGTH = 50;

	static final int MAX_PAGE_SIZE = 20;

	static final int MAX_REGION_IDS = 20;

	/** Libraries added by hand: at most 20 per account (cycle 06 spec R3). */
	static final int MAX_LIBRARY_IDS = 20;

	private static final Pattern POSITIVE = Pattern.compile("[1-9][0-9]{0,8}");

	private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,17}");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	private final MultiValueMap<String, String> params;

	BookParams(MultiValueMap<String, String> params) {
		this.params = params;
	}

	BookQuery query() {
		String title = field("title");
		String author = field("author");
		String publisher = field("publisher");
		if (title == null && author == null && publisher == null) {
			throw new InvalidBookParamException("title", "required", "one of title, author, publisher is required");
		}
		for (String value : new String[] { title, author, publisher }) {
			if (value != null) {
				int length = value.codePointCount(0, value.length());
				if (length < MIN_LENGTH || length > MAX_LENGTH) {
					throw new InvalidBookQueryException(length);
				}
			}
		}
		Set<String> exact = list("exact", Set.of("title", "author", "publisher"));
		Set<String> roleNames = list("roles", Set.of("translator", "illustrator", "editor"));
		BookQuery.Sort sort = switch (single("sort") == null ? "default" : single("sort").toLowerCase(Locale.ROOT)) {
			case "default" -> BookQuery.Sort.DEFAULT;
			case "newest" -> BookQuery.Sort.NEWEST;
			case "oldest" -> BookQuery.Sort.OLDEST;
			default -> throw new InvalidBookParamException("sort", "invalid_format",
					"sort must be default, newest or oldest");
		};
		planOptions(); // validated here to keep the error order; the controller reads it again
		int page = positive("page", 1, Integer.MAX_VALUE);
		int pageSize = positive("pageSize", MAX_PAGE_SIZE, MAX_PAGE_SIZE);
		Set<Role> roles = EnumSet.of(Role.AUTHOR, Role.UNKNOWN);
		roleNames.forEach(name -> roles.add(Role.valueOf(name.toUpperCase(Locale.ROOT))));
		return new BookQuery(title, author, publisher, exact.contains("title"), exact.contains("author"),
				exact.contains("publisher"), Set.copyOf(roles), sort, page, pageSize);
	}

	/**
	 * Missing scope means all, missing visit means any (same values as the site search). {@code libraryIds} are the
	 * libraries the user added by hand (cycle 06); unknown or disabled ones are ignored by the plan.
	 */
	PlanOptions planOptions() {
		String scope = single("scope");
		PlanOptions.Scope s = switch (scope == null ? "all" : scope.toLowerCase(Locale.ROOT)) {
			case "all" -> PlanOptions.Scope.ALL;
			case "joinable" -> PlanOptions.Scope.JOINABLE;
			default -> throw new InvalidBookParamException("scope", "invalid_format", "scope must be all or joinable");
		};
		String visit = single("visit");
		PlanOptions.Visit v = switch (visit == null ? "any" : visit.toLowerCase(Locale.ROOT)) {
			case "any" -> PlanOptions.Visit.ANY;
			case "none" -> PlanOptions.Visit.NONE;
			default -> throw new InvalidBookParamException("visit", "invalid_format", "visit must be any or none");
		};
		return new PlanOptions(s, v, ids("libraryIds", MAX_LIBRARY_IDS));
	}

	/** Same rule as the site search: comma-separated positive integers, at most 20 per field. */
	Set<Long> regionIds(String name) {
		return ids(name, MAX_REGION_IDS);
	}

	/** Comma-separated positive integers, duplicates merged, at most {@code max} after merging. */
	private Set<Long> ids(String name, int max) {
		String raw = single(name);
		if (raw == null) {
			return Set.of();
		}
		Set<Long> ids = new LinkedHashSet<>();
		for (String part : raw.split(",", -1)) {
			String trimmed = part.strip();
			if (!ID.matcher(trimmed).matches()) {
				throw new InvalidBookParamException(name, "invalid_format",
						name + " must be comma-separated positive integers");
			}
			ids.add(Long.parseLong(trimmed));
		}
		if (ids.size() > max) {
			throw new InvalidBookParamException(name, "too_many", "At most " + max + " ids are allowed");
		}
		return ids;
	}

	/** A condition field: trimmed, whitespace runs collapsed; blank means not given. */
	private String field(String name) {
		String value = single(name);
		return value == null ? null : WHITESPACE.matcher(value.strip()).replaceAll(" ");
	}

	private Set<String> list(String name, Set<String> allowed) {
		String raw = single(name);
		Set<String> values = new LinkedHashSet<>();
		if (raw == null) {
			return values;
		}
		for (String part : raw.split(",")) {
			String value = part.strip().toLowerCase(Locale.ROOT);
			if (value.isEmpty()) {
				continue;
			}
			if (!allowed.contains(value)) {
				throw new InvalidBookParamException(name, "invalid_format",
						name + " must be a comma-separated list of " + String.join(", ", allowed.stream().sorted().toList()));
			}
			values.add(value);
		}
		return values;
	}

	private int positive(String name, int missing, int max) {
		String raw = single(name);
		if (raw == null) {
			return missing;
		}
		if (!POSITIVE.matcher(raw.strip()).matches() || Integer.parseInt(raw.strip()) > max) {
			throw new InvalidBookParamException(name, "invalid_format",
					name + " must be an integer from 1" + (max == Integer.MAX_VALUE ? "" : " to " + max));
		}
		return Integer.parseInt(raw.strip());
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
			throw new InvalidBookParamException(name, "duplicate", name + " may appear only once");
		}
		return given.isEmpty() ? null : given.getFirst();
	}

}
