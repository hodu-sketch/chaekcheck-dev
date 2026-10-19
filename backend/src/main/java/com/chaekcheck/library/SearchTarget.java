package com.chaekcheck.library;

/**
 * One library in a search plan: what the availability module needs to query it and to describe it to the user.
 * @param baseUrl site root the adapter appends its path to, e.g. {@code https://www.ghlib.go.kr:446}
 * @param extraConditionText eligibility we cannot decide from regions, shown as text; may be null
 * @param joinUrl the library's join or eligibility page; may be null
 * @param joinCaution original text shown as a caution that does not change the eligibility; may be null
 * @param joinNotice neutral information shown as "안내" (who the library serves); may be null
 * @param homepageUrl front page of this row: the e-library's main page for EBOOK; may be null
 * @param chosen the user added this library by hand ({@link PlanOptions#libraryIds()}): it comes first and the
 * scope and visit filters keep it (ADR-0050). When no region rule matched, {@code eligibility} is UNKNOWN.
 * @param joinSurvey how far the join rules were researched; the screen picks its join notice by it (ADR-0053)
 * @param lendingModel every library but the integrated portals, null for the portals; on the KYOBO subscription
 * screen OWNED is read as counts (ADR-0056, ADR-0071)
 * @param logoUrl the library's own logo ({@code /library-logos/…}), or null when it has none or it was taken down
 * (ADR-0059)
 * @param mobileSiteDiffers the site's phone version shows another page; the web notes it on narrow screens (cycle 08)
 * @param vendor company of the site's system; with lendingModel and orderNo it tells same-name sites apart (ADR-0071)
 * @param orderNo 1, or 2 and up within the same (name, vendor, lendingModel) (ADR-0071)
 * @param visitRequired whether this user has to visit the library to join: the library's own flag, or
 * {@code visitOutsideRegion} when none of the user's regions matches its REGION rules (not ELIGIBLE). The visit filter
 * and the visit order read this value.
 * @param visitOutsideRegion the library's flag as stored: residents of its region join online, others only by a visit
 * (user 2026-10-20 "지역 외는 방문 가입")
 */
public record SearchTarget(long id, String name, LibraryType libraryType, PlatformType platformType, String baseUrl,
		PriorityGroup priorityGroup, boolean deferred, Eligibility eligibility, String eligibilitySummary,
		String extraConditionText, boolean visitRequired, String joinMethod, String joinUrl, String joinCaution,
		String joinNotice, String homepageUrl, boolean chosen, JoinSurvey joinSurvey, LendingModel lendingModel,
		String logoUrl, boolean mobileSiteDiffers, Vendor vendor, int orderNo, boolean visitOutsideRegion) {

	/**
	 * The user can join for sure: anyone may join, or one of the user's regions matches. An extra condition (e.g.
	 * 책이음 members) is only extra information then (spec AC-12, user decision 2026-09-29). Libraries shown with
	 * "가입 자격 확인 필요" (UNKNOWN, UNVERIFIED) are not joinable.
	 */
	public boolean joinable() {
		return eligibility == Eligibility.OPEN || eligibility == Eligibility.ELIGIBLE;
	}

}
