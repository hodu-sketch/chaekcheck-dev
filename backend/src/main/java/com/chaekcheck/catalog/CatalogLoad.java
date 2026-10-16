package com.chaekcheck.catalog;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

	/*
	 * Separate import (cycle 11, ADR-0078): the PC fills catalog_next and exports it as one pg_dump -Fc file with a
	 * small table catalog_next.load_manifest; the production database restores the file and swaps it in; the PC
	 * then swaps its own catalog_next in (finish), or drops it (discard).
	 *
	 * Waiting new tables: catalog_next with load_manifest is a file waiting for the PC's finish. replaceAll and
	 * prepare do not touch it and stop ("first finish or discard"); catalog_next without load_manifest was left by a
	 * load that stopped and is dropped as before.
	 */

	/**
	 * Fills {@code catalog_next} without indexes and keeps it, for {@link #export}. Ids carry over from this
	 * database's {@code catalog} as in {@link #replaceAll}.
	 * @return the counts and the steps up to {@code write}
	 * @throws IllegalStateException when new tables are waiting (see above); nothing is dropped then
	 */
	Result prepare(String source, Loader loader);

	/**
	 * Writes {@code load_manifest} and dumps {@code catalog_next} into {@code file} (written as {@code file.part} and
	 * renamed when the dump is read back). {@code catalog_next} stays, waiting for {@link #finishPrepared}. When it
	 * fails, {@code catalog_next} is dropped.
	 * @param notes JSON kept in the manifest for the caller; this module does not read it
	 */
	Manifest export(Path file, String notes);

	/** The manifest of an exported file, read without restoring the file. */
	Manifest readManifest(Path file);

	/** This database's {@code catalog} values the files are checked against. */
	Baseline baseline();

	/**
	 * Restores an exported file into {@code catalog_next}, makes the indexes of this database's {@code catalog}, and
	 * swaps it in. The file's baseline must equal {@link #baseline()} ({@code mismatch} otherwise, nothing touched).
	 * When restoring, indexing or the swap fails, {@code catalog_next} is dropped and the catalog stays.
	 * @return counts of the restored tables (ids carried over: 0) and the steps {@code restore}, {@code index},
	 * {@code analyze}, {@code swap}
	 */
	Result apply(Path file);

	/**
	 * Swaps in the waiting {@code catalog_next} of {@code file} after the production apply. Indexes left by a finish
	 * that stopped are dropped first. When it fails, the new tables keep waiting, so it can run again.
	 * @return the steps {@code index}, {@code analyze}, {@code swap}
	 * @throws IllegalStateException when no new tables of this file are waiting (nothing is touched)
	 */
	Result finishPrepared(Path file);

	/**
	 * A {@link BookLookup} that reads the {@code catalog_next} {@link #prepare} filled, with the ids that file will
	 * carry, so the import can match its recommended lists before {@link #export} (ADR-0124). Valid until the export
	 * fails, the finish swaps it in or it is discarded; the tables have no indexes, so each lookup is one query that
	 * reads them whole.
	 * @throws IllegalStateException when there is no {@code catalog_next}
	 */
	BookLookup preparedLookup();

	/** Whether new tables of an export are waiting here, so an import can stop before reading anything. */
	boolean hasWaitingExport();

	/** Drops {@code catalog_next} whatever it holds; no import row. */
	Discarded discardPrepared();

	/** What {@link #discardPrepared()} dropped. */
	enum Discarded {

		/** No {@code catalog_next}. */
		NOTHING,
		/** {@code catalog_next} without a manifest, left by a load that stopped. */
		LEFT_OVER,
		/** New tables waiting for the finish. */
		WAITING

	}

	/**
	 * Values of the {@code catalog} tables two databases are compared by (ADR-0078).
	 * @param bookSequence the highest id the book identity ever gave ({@code last_value}, less one when not called)
	 * @param columnsHash SHA-256 (hex) of the (table, column, type) list of the four catalog tables
	 */
	record Baseline(long bookCount, long bookMaxId, long bookSequence, long holdingCount, long holdingMaxId,
			long holdingSequence, String columnsHash) {
	}

	/**
	 * The one row of {@code catalog_next.load_manifest}.
	 * @param baseline the exporting database's {@code catalog} values when the file was made
	 * @param notes the caller's JSON, as stored
	 */
	record Manifest(int formatVersion, UUID exportId, Instant exportedAt, Baseline baseline, String notes) {
	}

	/**
	 * The new tables were not swapped in; the previous catalog stays.
	 * @param reason {@code lock_not_acquired} (the tables stayed locked through every attempt),
	 * {@code outside_foreign_key} (a table of another module points at a catalog table, handoff C2) or
	 * {@code mismatch} (the file was made from a database whose catalog differs, ADR-0078)
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
