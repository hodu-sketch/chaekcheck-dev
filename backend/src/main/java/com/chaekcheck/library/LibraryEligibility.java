package com.chaekcheck.library;

import java.util.List;

/**
 * One enabled library with the user's eligibility for the regions sent (cycle 06 public list, ADR-0049).
 * @param eligibility as in the search plan, except that a library whose region rules match none of the regions sent
 * is not left out but {@link Eligibility#UNKNOWN}: "the regions sent do not make you eligible", which differs from
 * the plan's "no regions sent"
 * @param matchedBy which of the user's regions made the library ELIGIBLE: RESIDENCE, WORK or both; empty otherwise
 * @param cautioned the library has a join caution (shown on the search results, not here)
 * @param eligibilitySummary short eligibility text; may be null
 */
public record LibraryEligibility(long id, String name, Eligibility eligibility, List<RegionBasis> matchedBy,
		boolean visitRequired, boolean cautioned, String eligibilitySummary) {

	public LibraryEligibility {
		matchedBy = List.copyOf(matchedBy);
	}

	/** Which of the user's regions a rule matched. */
	public enum RegionBasis {

		RESIDENCE, WORK

	}

}
