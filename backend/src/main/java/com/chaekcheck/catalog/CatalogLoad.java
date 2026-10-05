package com.chaekcheck.catalog;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;

/**
 * Replaces the whole collected catalog (ADR-0031, ADR-0060): the {@code collection} import builds the books and
 * streams them in; this module owns the tables. Everything is written in one transaction with PostgreSQL COPY, so a
 * failed load leaves the previous catalog; the search waits while the tables are locked (accepted before deployment,
 * spec R7-5). {@code VACUUM (ANALYZE)} runs after the commit.
 */
public interface CatalogLoad {

	/**
	 * @param source what was loaded (e.g. the SQLite file name), for the log
	 * @param loader writes every book first, then every holding, then the library snapshots
	 */
	Result replaceAll(String source, Loader loader);

	/** Fills one load. Books get ids 1, 2, … in the order they are added. */
	@FunctionalInterface
	interface Loader {

		void load(Session session) throws Exception;

	}

	/**
	 * One load in progress. Rows are streamed to the database as they come, so the whole catalog never has to be in
	 * memory at once. All books must come before the first holding.
	 */
	interface Session {

		/** @return the new book's id */
		long addBook(NewBook book);

		/** @throws IllegalStateException when a book is added after this */
		void addHolding(NewHolding holding);

		/** The day the library's list was collected, shown as the search's reference date. */
		void addLibrarySnapshot(long libraryId, Instant collectedAt);

	}

	/**
	 * @param libraryIds the service libraries holding the book, sorted, no duplicates (ADR-0055)
	 * @param contentType {@code EBOOK}, {@code KIDS_BOOK}, {@code PAPER} or {@code UNKNOWN}
	 */
	record NewBook(String title, String authorDisplay, String publisher, String titleNorm, String mainTitleNorm,
			String publisherNorm, LocalDate publishedOn, String isbn13, String contentType, long[] libraryIds,
			List<Contributor> contributors) {
	}

	/** One library's copy of a book, with the collected text kept as it was. */
	record NewHolding(long bookId, long libraryId, String platform, String itemKey, String detailUrl,
			String contentType, String titleRaw, String authorRaw, String publisherRaw, String publishedRaw,
			String supplier, String altCode, Instant firstSeenAt) {
	}

	record Result(long books, long contributors, long holdings, long librarySnapshots) {
	}

}
