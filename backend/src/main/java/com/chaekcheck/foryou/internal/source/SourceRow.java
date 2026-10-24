package com.chaekcheck.foryou.internal.source;

/**
 * One row a popular source gave, before matching.
 * @param itemKey the site's item number (e-library sites), or null
 * @param isbn13 the ISBN-13 (sales rankings), or null
 * @param loansNow on loan now (e-library sites), or null
 * @param reservations reservations now (e-library sites), or null
 */
public record SourceRow(int rank, String title, String author, String publisher, String isbn13, String itemKey,
		Integer loansNow, Integer reservations) {
}
