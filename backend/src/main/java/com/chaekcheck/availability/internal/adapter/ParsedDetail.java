package com.chaekcheck.availability.internal.adapter;

/**
 * What a book's detail page shows (ADR-0029). Count screens fill the counts; the portal fills only
 * {@code siteVerdict}. A count screen whose counts are replaced by a notice (Gangnam "예약정원이 마감되었습니다.") has
 * null counts and {@link SiteVerdict#OTHER}.
 */
public record ParsedDetail(Integer holdings, Integer loaned, Integer reservations, SiteVerdict siteVerdict) {

	public static ParsedDetail counted(int holdings, int loaned, int reservations) {
		return new ParsedDetail(holdings, loaned, reservations, null);
	}

	public static ParsedDetail verdict(SiteVerdict verdict) {
		return new ParsedDetail(null, null, null, verdict);
	}

}
