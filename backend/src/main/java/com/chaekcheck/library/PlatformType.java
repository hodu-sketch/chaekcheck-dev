package com.chaekcheck.library;

/**
 * Kind and generation of an e-library search screen; selects the availability adapter (ADR-0016). Every kind
 * carries a generation number so a new screen of the same vendor becomes the next number and existing names never
 * change (user decision 2026-09-28).
 */
public enum PlatformType {
	/** KYOBO regular site ({@code /elibrary-front}), counts shown. */
	KYOBO_V1,
	/** KYOBO subscription site, no counts. */
	KYOBO_SUBSCRIPTION_V1,
	/** YES24 previous-generation screen, EUC-KR. */
	YES24_V1,
	/** YES24 current screen, UTF-8. */
	YES24_V2,
	/** YES24 current screen layout served in EUC-KR (search text must be EUC-KR). */
	YES24_V3,
	/** Regional / education-office integrated portal ({@code /elib/module/elib/..}). */
	ELIB_PORTAL_V1,
	/** Gangnam-gu's own system, EUC-KR. */
	GANGNAM_V1,
	/**
	 * Bookcube FxLibrary site ({@code .../FxLibrary}): counts read from the book's XML detail; a rental site
	 * ({@code volume} 0 with {@code nowRentalCount}) is a subscription (ADR-0056).
	 */
	BOOKCUBE_FX,
	/** Aladin e-library site ({@code ?nav_code=}): counts read from the detail page's "보유현황" (ADR-0056). */
	ALADIN_V1,
	/**
	 * 서울도서관's own integrated e-book site ({@code elib.seoul.go.kr}), read through its JSON: owned titles with
	 * counts, subscription titles without a limit (library-maintenance 4-14).
	 */
	SEOUL_ELIB_V1
}
