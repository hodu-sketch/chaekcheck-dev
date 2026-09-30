package com.chaekcheck.catalog.internal.importer;

import java.time.Instant;
import java.time.LocalDate;

/**
 * One holding row of the collected SQLite that passed the import filters.
 * @param platformType the library's screen kind in the service ({@code PlatformType} name)
 * @param publishedOn the parsed date; null when missing, unreadable or in the future
 */
record CollectedRow(long libraryId, String platformType, String itemKey, String detailUrl, String contentType,
		String title, String authorRaw, String publisher, String publishedRaw, LocalDate publishedOn, String supplier,
		String altCode, Instant firstSeenAt) {
}
