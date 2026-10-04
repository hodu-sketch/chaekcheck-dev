package com.chaekcheck.library;

/**
 * Search priority group of a library (design-delta "우선순위 그룹 계산", spec 5.1). Order with regions:
 * MY_SIGUNGU, MY_SIDO, OPEN, NEEDS_CHECK. Without regions: OPEN, QUALIFIED, NEEDS_CHECK.
 */
public enum PriorityGroup {
	/** A RESIDENCE or WORK rule names one of the chosen SIGUNGU. */
	MY_SIGUNGU,
	/** A RESIDENCE or WORK rule names the SIDO of a chosen SIGUNGU. */
	MY_SIDO,
	/** Anyone can join (NATIONWIDE rule). */
	OPEN,
	/**
	 * No regions sent and the library has region rules; with regions, a library the user added by hand whose region
	 * rules do not match (cycle 06, ADR-0050).
	 */
	QUALIFIED,
	/** Eligibility not verified, or only a condition we cannot decide (extra condition text). */
	NEEDS_CHECK
}
