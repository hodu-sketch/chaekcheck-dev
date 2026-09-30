package com.chaekcheck.availability.internal.adapter;

/**
 * One search result as the library site shows it. Descriptions and covers are never read (AC-11).
 * @param holdings null on screens without counts (portal, subscription)
 * @param loaned null when the screen does not show it
 * @param reservations null when the screen does not show it
 * @param detailPath the book's own page, relative to the search page URL (e.g.
 * {@code /ebook/detail/?goods_id=29090201} or {@code ../book/view.do?..}); null when the page does not link one
 * @param siteVerdict what the site writes as availability; null on count screens
 */
public record ParsedItem(String title, String author, String publisher, String supplier, Integer holdings,
		Integer loaned, Integer reservations, String detailPath, SiteVerdict siteVerdict) {

	/** A row of a count screen (holdings, loaned and reservations shown). */
	public static ParsedItem counted(String title, String author, String publisher, String supplier, int holdings,
			int loaned, int reservations, String detailPath) {
		return new ParsedItem(title, author, publisher, supplier, holdings, loaned, reservations, detailPath, null);
	}

}
