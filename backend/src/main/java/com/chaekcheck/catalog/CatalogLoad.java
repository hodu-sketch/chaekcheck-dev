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
 * <p>
 * Book ids carry over from the previous load (plan {@code catalog-refresh.md} 5.2, user request 2026-10-05): a new
 * book takes the id of the old book whose holdings (library + the library's item key) it shares most. When old
 * books merge into one, the smallest old id stays; when an old book splits, the new book sharing most of its
 * holdings keeps the id. Books that share nothing get new ids above every id ever given, so an id is never reused;
 * the ids of merged-away books are gone ("책을 찾을 수 없어요"), with no forwarding table.
 */
public interface CatalogLoad {

	/**
	 * @param source what was loaded (e.g. the SQLite file name), for the log
	 * @param loader writes every book first, then every holding, then the library snapshots
	 */
	Result replaceAll(String source, Loader loader);

	/**
	 * Fills one load. {@link Session#addBook} returns an id that only links the book's holdings within this load;
	 * the stored id is decided at the end (see the type comment).
	 */
	@FunctionalInterface
	interface Loader {

		void load(Session session) throws Exception;

	}

	/**
	 * One load in progress. Rows are streamed to the database as they come, so the whole catalog never has to be in
	 * memory at once. All books must come before the first holding.
	 */
	interface Session {

		/** @return the book's id within this load, for {@link NewHolding#bookId()} */
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

	/**
	 * One library's copy of a book, with the collected text kept as it was.
	 * @param bookId the id {@link Session#addBook} returned
	 */
	record NewHolding(long bookId, long libraryId, String platform, String itemKey, String detailUrl,
			String contentType, String titleRaw, String authorRaw, String publisherRaw, String publishedRaw,
			String supplier, String altCode, Instant firstSeenAt) {
	}

	/**
	 * @param keptBookIds books that took an id from the previous load
	 * @param newBookIds books that got a new id
	 * @param droppedBookIds ids of the previous load that no book has now (merged away or gone)
	 */
	record Result(long books, long contributors, long holdings, long librarySnapshots, long keptBookIds,
			long newBookIds, long droppedBookIds) {
	}

}
