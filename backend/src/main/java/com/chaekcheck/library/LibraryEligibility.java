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
 * @param joinSurvey how far the join rules were researched (ADR-0053)
 * @param homepageUrl the e-library's main page, opened from the logo; may be null
 * @param logoUrl the library's own logo, or null (ADR-0059)
 * @param mobileSiteDiffers the site's phone version shows another page (cycle 08)
 * @param vendor company of the site's system (ADR-0071); the web shows it after the name (ADR-0076)
 * @param lendingModel every library but the integrated portals, null for the portals (ADR-0071)
 * @param orderNo 1, or 2 and up within the same (name, vendor, lendingModel) (ADR-0071)
 */
public record LibraryEligibility(long id, String name, Eligibility eligibility, List<RegionBasis> matchedBy,
		boolean visitRequired, boolean cautioned, String eligibilitySummary, JoinSurvey joinSurvey,
		String homepageUrl, String logoUrl, boolean mobileSiteDiffers, Vendor vendor, LendingModel lendingModel,
		int orderNo) {

	public LibraryEligibility {
		matchedBy = List.copyOf(matchedBy);
	}

	/** Which of the user's regions a rule matched. */
	public enum RegionBasis {

		RESIDENCE, WORK

	}

}
