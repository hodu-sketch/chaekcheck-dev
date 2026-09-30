package com.chaekcheck.catalog;

/**
 * One library's copy of a book in the collected catalog.
 * @param platformType the library's screen kind when collected (a {@code PlatformType} name)
 * @param detailUrl the book's detail page on the library site (http(s))
 * @param title collected title, shown for rows whose page is not read (subscription); may be null
 * @param author collected author text; may be null
 * @param publisher collected publisher; may be null
 */
public record HoldingRef(long holdingId, long bookId, long libraryId, String platformType, String detailUrl,
		String title, String author, String publisher) {
}
