package com.chaekcheck.collection.internal.importer;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryType;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Counts logged at the end of an import, kept in {@code collection.catalog_import} and written to plan-feedback and
 * docs/maintenance/3-import.md.
 */
final class ImportReport {

	/** The collection DB file name (cycle 11: carried in the export file's notes to the production row). */
	String source;

	long rowsRead;

	/** Skipped holding rows by reason (the keys are written as JSON, so plain lower-case words only). */
	final Map<String, Long> skipped = new TreeMap<>();

	final Map<String, Long> kyoboCodes = new TreeMap<>();

	/** Service libraries whose rows are imported: registered (collection id below 100001) and the others, added later from the collected list. */
	int registeredLibraries;

	int otherLibraries;

	int librariesWithoutFullSuccess;

	/** Collection libraries without an enabled service library (no link, or turned off). */
	int librariesNotInService;

	/** Linked libraries whose base URL differs from the service library's (a safety check). */
	int librariesBaseUrlMismatch;

	/** Dates before 1950 of the placeholder-date libraries, set to null. */
	long placeholderDatesCleared;

	/** Rows whose author and publisher were read swapped (cycle 19, ADR-0115: author_publisher_swapped.tsv). */
	long authorPublisherSwappedRows;

	long booksAfterPlatformCode;

	/**
	 * Step ② joins made by a supplier code key (cycle 19, ADR-0114): rows or groups joined to another group only through
	 * the same (supplier family, code). Log only.
	 */
	long supplierCodeJoins;

	/**
	 * Groups after step ② joined through a supplier code that hold two or more distinct ISBNs (cycle 19, ADR-0114). A
	 * stop condition of the cycle 19 comparison (handoff C4-2): above 0, plan decides before a real import.
	 */
	long codeJoinedIsbnConflicts;

	long booksAfterKyoboIsbn;

	long booksAfterNationalBibliography;

	long booksAfterSameText;

	/** Groups that step ④ merged into another group. */
	long sameTextMerged;

	/**
	 * Books step ④ kept apart by their ISBNs (cycle 12, R1): merge keys minus same-text keys, where a key with two or
	 * more distinct ISBNs has one merge key per ISBN and one for its groups without an ISBN. Log only.
	 */
	long sameTextIsbnSplit;

	/**
	 * Books step ④ kept apart by their subtitles (cycle 12, user decision 2026-10-13): merge keys minus (key, ISBN
	 * part) pairs, where a key with two or more different subtitles has one merge key per subtitle and one for its
	 * groups without one. Log only.
	 */
	long sameTextSubtitleSplit;

	/**
	 * Groups without a same-text key only because their author or publisher is a placeholder (cycle 12, R2):
	 * {@link SameTextKey.Key#placeholderOnly()}. Log only.
	 */
	long sameTextPlaceholderNoKey;

	/** Groups whose step ④ comparison title lost a leading bracket note (cycle 19 R2-2). Log only. */
	long leadingNoteCut;

	/** Merged books whose groups' dates are 2+ years apart (maybe different editions). */
	long sameTextWideDates;

	final List<String> sameTextWideDateSamples = new ArrayList<>();

	long nationalBibliographyRecords;

	long nationalBibliographyIsbnAttached;

	long nationalBibliographyAmbiguous;

	/** The records came from the stored table (same TTL fingerprint), not from the TTL files (ADR-0069). */
	boolean nationalBibliographyReused;

	/** Time spent making the records' keys, inside the national bibliography step. */
	Duration nationalBibliographyNormalize = Duration.ZERO;

	long books;

	long contributors;

	long holdings;

	/** Book ids (plan catalog-refresh.md 5.2): kept from the previous import, new, and old ids no book has now. */
	long keptBookIds;

	long newBookIds;

	long droppedBookIds;

	/**
	 * Time of each step in order (cycle 08 handoff step 1), kept as {@code step_seconds}: {@code read} (first pass),
	 * {@code group} (codes), {@code nb} (national bibliography records read), {@code normalize} (their keys),
	 * {@code same_text}, the steps of {@link com.chaekcheck.catalog.CatalogLoad.Result#steps()}, then {@code load}
	 * (the whole {@code replaceAll}, from the first book to the swap).
	 */
	final Map<String, Duration> steps = new LinkedHashMap<>();

	/**
	 * Oldest and newest collected time of the imported libraries: the same values as the library snapshots (AC-7).
	 * Null when no library was imported.
	 */
	Instant collectedFrom;

	Instant collectedTo;

	/** Peak heap use in MB: the sum of the heap pools' peaks, so an upper bound. */
	long maxHeapMb;

	void skip(String reason) {
		skipped.merge(reason, 1L, Long::sum);
	}

	long skippedCount() {
		return skipped.values().stream().mapToLong(Long::longValue).sum();
	}

	void collected(Instant collectedAt) {
		collectedFrom = collectedFrom == null || collectedAt.isBefore(collectedFrom) ? collectedAt : collectedFrom;
		collectedTo = collectedTo == null || collectedAt.isAfter(collectedTo) ? collectedAt : collectedTo;
	}

	void noteHeap() {
		long peak = ManagementFactory.getMemoryPoolMXBeans()
			.stream()
			.filter(pool -> pool.getType() == MemoryType.HEAP)
			.mapToLong(pool -> pool.getPeakUsage().getUsed())
			.sum();
		maxHeapMb = Math.max(maxHeapMb, peak >> 20);
	}

	@Override
	public String toString() {
		return """
				collection import
				  rows read: %d, skipped: %d %s
				  libraries: registered %d, others %d (without a full success, collected_at from last_seen_at: %d); \
				not in service %d, base URL mismatch %d
				  placeholder dates cleared: %d; author and publisher read swapped: %d rows
				  kyobo codes: %s
				  (2) supplier code joins %d, code-joined groups with 2+ ISBNs %d
				  books after (1) platform code: %d, (2) kyobo ISBN: %d, (3) national bibliography: %d, (4) same text: %d
				  (4) same text: groups merged %d, kept apart by ISBN %d, by subtitle %d, no key for a placeholder %d, leading note cut %d, merged books with dates 2+ years apart %d
				  national bibliography: rows (one per record ISBN) %d (%s), ISBN attached %d, ambiguous keys %d
				  written: books %d, contributors %d, holdings %d
				  book ids: kept %d, new %d, dropped %d
				  collected: %s ~ %s
				  time: %s; heap used up to %d MB (max %d MB)"""
			.formatted(rowsRead, skippedCount(), skipped, registeredLibraries, otherLibraries,
					librariesWithoutFullSuccess, librariesNotInService, librariesBaseUrlMismatch, placeholderDatesCleared,
					authorPublisherSwappedRows, kyoboCodes, supplierCodeJoins, codeJoinedIsbnConflicts, booksAfterPlatformCode, booksAfterKyoboIsbn, booksAfterNationalBibliography,
					booksAfterSameText, sameTextMerged, sameTextIsbnSplit, sameTextSubtitleSplit, sameTextPlaceholderNoKey,
					leadingNoteCut, sameTextWideDates, nationalBibliographyRecords,
					nationalBibliographyReused ? "from the stored table" : "from the TTL files",
					nationalBibliographyIsbnAttached, nationalBibliographyAmbiguous, books, contributors, holdings,
					keptBookIds, newBookIds, droppedBookIds,
					collectedFrom, collectedTo, steps, maxHeapMb, Runtime.getRuntime().maxMemory() >> 20);
	}

}
