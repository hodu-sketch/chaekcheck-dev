package com.chaekcheck.catalog;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.chaekcheck.catalog.normalize.Contributors.Contributor;

/**
 * Replaces the whole collected catalog (ADR-0031, ADR-0060): the {@code collection} import builds the books and
 * streams them in; this module owns the tables. Since cycle 08 (ADR-0065) the load is written into new tables while
 * the search keeps reading the previous ones, and the new tables are swapped in by one short transaction; the search
 * waits only for that swap. A load that fails before the swap leaves the previous catalog as it was.
 * <p>
 * Book ids carry over from the previous load (plan {@code catalog-refresh.md} 5.2, user request 2026-10-05): a new
 * book takes the id of the old book whose holdings (library + the library's item key) it shares most. When old
 * books merge into one, the smallest old id stays; when an old book splits, the new book sharing most of its
 * holdings keeps the id. Books that share nothing get new ids above every id ever given, so an id is never reused;
 * the ids of merged-away books are gone ("책을 찾을 수 없어요"), with no forwarding table.
 * <p>
 * Holding ids carry over too (ADR-0070): the same library and item key keep the holding id, new holdings get ids above
 * every holding id given before, and the ids of holdings that left are not given again.
 */
public interface CatalogLoad {

	/**
	 * @param source what was loaded (e.g. the SQLite file name), for the log
	 * @param loader writes every book first, then every holding, then the library snapshots
	 */
	Result replaceAll(String source, Loader loader);

	/**
	 * The new tables were not swapped in; the previous catalog stays.
	 * @param reason {@code lock_not_acquired} (the tables stayed locked through every attempt) or
	 * {@code outside_foreign_key} (a table of another module points at a catalog table, handoff C2)
	 */
	final class NotSwappedException extends IllegalStateException {

		private final String reason;

		public NotSwappedException(String reason, String message) {
			super(message);
			this.reason = reason;
		}

		public String reason() {
			return reason;
		}

	}

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
	 * @param steps time of each step of the load, in order (cycle 08 handoff steps 1 and 4): {@code books_copy} (books
	 * and contributors into temporary tables), {@code holdings_read} (the loader's second pass without the COPY),
	 * {@code holdings_copy}, {@code match} (book and holding ids), {@code write} (the new tables), {@code index}
	 * (indexes and constraints), {@code analyze} ({@code VACUUM (ANALYZE)}) and {@code swap} (the search waits
	 * only during this, lock waits included)
	 */
	record Result(long books, long contributors, long holdings, long librarySnapshots, long keptBookIds,
			long newBookIds, long droppedBookIds, Map<String, Duration> steps) {

		public Result {
			steps = Collections.unmodifiableMap(new LinkedHashMap<>(steps));
		}

	}

}
