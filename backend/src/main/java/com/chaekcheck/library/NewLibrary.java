package com.chaekcheck.library;

import java.time.LocalDate;
import java.util.List;

/**
 * A library to add through {@link LibraryRegistry#add} (cycle 13, ADR-0083): the columns of {@code library.library}
 * that the old seed script wrote ({@code build_library_seed.py} {@code LIBRARY_FIELDS}), with real nulls and
 * booleans. A new row is enabled; library type, logo and the timestamps keep their column defaults.
 * @param id chosen by the caller ({@link LibraryRegistry#nextId()} and up)
 * @param lendingModel null for the integrated portals (ADR-0071)
 * @param orderNo 1, or 2 and up within the same (name, vendor, lending model)
 * @param homepageUrl for a collected library the same as {@code baseUrl} (spec R2-6)
 * @param rules eligibility rules; none means the eligibility is not verified
 */
public record NewLibrary(long id, String name, PlatformType platformType, Vendor vendor, LendingModel lendingModel,
		int orderNo, String baseUrl, String homepageUrl, String eligibilitySummary, String extraConditionText,
		boolean visitRequired, String joinMethod, String joinUrl, String joinCaution, String joinNotice,
		Integer holdingsCount, LocalDate holdingsCountedOn, boolean deferred, JoinSurvey joinSurvey,
		List<NewEligibilityRule> rules) {

	public NewLibrary {
		rules = rules == null ? List.of() : List.copyOf(rules);
	}

}
