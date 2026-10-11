package com.chaekcheck.popular.internal.fetch;

/**
 * One book of a ranking list, as read from the site (plain text and attribute values only, plan cycle 10 C7).
 * @param rank position in the list, 1 to 50
 * @param isbn13 without hyphens; null when the site's value is empty or not an ISBN-13
 * @param title shown when there is no cover
 * @param thema the site's sub-category text; null when empty
 * @param coverPath the small cover's path on the same site; null when missing or not a safe path
 */
public record RankedBook(int rank, String isbn13, String title, String thema, String coverPath) {
}
