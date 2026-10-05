package com.chaekcheck.availability.internal.adapter;

/**
 * Availability as a site writes it on a row ("대출 가능 여부"), for screens without counts to compute it from.
 * Count screens leave it null.
 */
public enum SiteVerdict {
	/** "대출 가능" */
	AVAILABLE,
	/** "예약 가능" */
	RESERVABLE,
	/** The field is there but empty. */
	BLANK,
	/** Any other text. */
	OTHER,
	/**
	 * A rental (subscription) title: any number of users at once, so its counts mean nothing (Bookcube rental sites,
	 * ADR-0056). Decided like the KYOBO subscription screen.
	 */
	SUBSCRIPTION;

	/** Maps the text after "대출 가능 여부:". */
	public static SiteVerdict of(String text) {
		String value = text == null ? "" : text.strip();
		if (value.isEmpty()) {
			return BLANK;
		}
		return switch (value) {
			case "대출 가능", "대출가능" -> AVAILABLE;
			case "예약 가능", "예약가능" -> RESERVABLE;
			default -> OTHER;
		};
	}

}
