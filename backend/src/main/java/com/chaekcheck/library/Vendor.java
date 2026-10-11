package com.chaekcheck.library;

/**
 * Company whose e-library system a site runs on. With {@link LendingModel} and the order number it tells sites of
 * the same body apart, so library names carry the body's name only (ADR-0071). The library collection decides it and
 * the seed script writes it; the database only checks the allowed values. The web turns it into text (ADR-0076).
 */
public enum Vendor {
	/** 교보문고: the KYOBO and KYOBO subscription screens. */
	KYOBO,
	/** YES24, all screen generations. */
	YES24,
	/** 북큐브. */
	BOOKCUBE,
	/** 알라딘. */
	ALADIN,
	/** Regional integrated portals (통합 포털); their lending model is null. */
	PORTAL,
	/** The library's own system (강남구). */
	IN_HOUSE
}
