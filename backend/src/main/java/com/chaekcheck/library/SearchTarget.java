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
 */
public record SearchTarget(long id, String name, LibraryType libraryType, PlatformType platformType, String baseUrl,
		PriorityGroup priorityGroup, boolean deferred, Eligibility eligibility, String eligibilitySummary,
		String extraConditionText, boolean visitRequired, String joinMethod, String joinUrl, String joinCaution,
		String joinNotice, String homepageUrl, boolean chosen) {

	/**
	 * The user can join for sure: anyone may join, or one of the user's regions matches. An extra condition (e.g.
	 * 책이음 members) is only extra information then (spec AC-12, user decision 2026-09-29). Libraries shown with
	 * "가입 자격 확인 필요" (UNKNOWN, UNVERIFIED) are not joinable.
	 */
	public boolean joinable() {
		return eligibility == Eligibility.OPEN || eligibility == Eligibility.ELIGIBLE;
	}

}
