package com.chaekcheck.collection.internal.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.Manifest;
import com.chaekcheck.collection.internal.libraryinsert.LibraryPart;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRegistry;

import tools.jackson.databind.json.JsonMapper;

/**
 * The separate import (cycle 11, ADR-0078): the PC exports, the production database applies, the PC finishes or
 * discards. See docs/maintenance/3-import.md 13.
 * <ul>
 * <li>{@code export}: read and group as an in-place import, fill {@code catalog_next}, write it into one file with
 * the PC's import record, library hash and link hash as the manifest's notes. No import row when it works.</li>
 * <li>{@code apply}: first the library part (cycle 13, ADR-0086, ADR-0093): in one transaction under the library
 * lock, the PC's succeeded library-insert runs not here yet (libraries, eligibility rules, links, run rows), then the
 * comparison of the file with this database (libraries, links, catalog baseline, table columns). When anything
 * differs, roll the library part back, keep a {@code FAILED mismatch} row and touch nothing. Otherwise commit it,
 * then restore, index and swap, and keep one row: the PC's counts and steps, then this database's steps, with the
 * file's {@code export_id}. When the library part fails, nothing starts ({@code FAILED error}, "library part"). When
 * the books fail after the library part was committed, the libraries stay; a new apply skips them.</li>
 * <li>{@code finish}: swap in the PC's waiting {@code catalog_next}; one row with the {@code export_id}.</li>
 * <li>{@code discard}: drop {@code catalog_next}; no row.</li>
 * </ul>
 */
class ImportTransfer {

	private static final Logger log = LoggerFactory.getLogger(ImportTransfer.class);

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final CatalogImporter importer;

	private final CatalogLoad catalogLoad;

	private final LibraryCatalog libraryCatalog;

	private final JdbcClient jdbc;

	private final ImportRecords records;

	private final Clock clock;

	private final LibraryRegistry libraryRegistry;

	private final TransactionTemplate transactions;

	ImportTransfer(CatalogImporter importer, CatalogLoad catalogLoad, LibraryCatalog libraryCatalog,
			LibraryRegistry libraryRegistry, JdbcClient jdbc, PlatformTransactionManager transactionManager,
			ImportRecords records, Clock clock) {
		this.importer = importer;
		this.catalogLoad = catalogLoad;
		this.libraryCatalog = libraryCatalog;
		this.libraryRegistry = libraryRegistry;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.records = records;
		this.clock = clock;
	}

	/**
	 * What an export carries in the manifest's notes: the PC import's record (as its {@code catalog_import} row
	 * would have it) and what the production database must share.
	 * @param stepSeconds the PC's steps up to {@code write}, in order
	 * @param libraryPart the PC's succeeded library-insert runs (cycle 13, manifest format 2)
	 */
	record Notes(String source, String grouping, long books, long contributors, long holdings,
			Map<String, Long> skipped, int registeredLibraries, int otherLibraries, long keptBookIds,
			long newBookIds, long droppedBookIds, Instant collectedFrom, Instant collectedTo,
			Map<String, Double> stepSeconds, long maxHeapMb, DatabaseFingerprint fingerprint,
			List<LibraryPart.Run> libraryPart) {

		static Notes of(ImportReport report, String grouping, DatabaseFingerprint fingerprint,
				List<LibraryPart.Run> libraryPart) {
			Map<String, Double> seconds = new LinkedHashMap<>();
			report.steps.forEach((step, time) -> seconds.put(step, time.toMillis() / 1000.0));
			return new Notes(report.source, grouping, report.books, report.contributors, report.holdings,
					new TreeMap<>(report.skipped), report.registeredLibraries, report.otherLibraries,
					report.keptBookIds, report.newBookIds, report.droppedBookIds, report.collectedFrom,
					report.collectedTo, seconds, report.maxHeapMb, fingerprint, libraryPart);
		}

		/** The PC's record, to which this database's steps are added. */
		ImportReport report() {
			ImportReport report = new ImportReport();
			report.source = source;
			report.books = books;
			report.contributors = contributors;
			report.holdings = holdings;
			report.skipped.putAll(skipped);
			report.registeredLibraries = registeredLibraries;
			report.otherLibraries = otherLibraries;
			report.keptBookIds = keptBookIds;
			report.newBookIds = newBookIds;
			report.droppedBookIds = droppedBookIds;
			report.collectedFrom = collectedFrom;
			report.collectedTo = collectedTo;
			stepSeconds.forEach((step, seconds) -> report.steps.put(step, Duration.ofMillis(Math.round(seconds * 1000))));
			return report;
		}

	}

	/** Mode {@code export}. */
	Manifest export(Path sqlite, Path ttlDirectory, Path file, String grouping) throws SQLException, IOException {
		requireNoWaitingExport();
		if (Files.exists(file)) {
			throw new IllegalStateException("The export file exists already: " + file);
		}
		Instant started = clock.instant();
		ImportReport report = importer.prepare(sqlite, ttlDirectory);
		String notes = JSON.writeValueAsString(Notes.of(report, grouping, DatabaseFingerprint.of(libraryCatalog, jdbc),
				LibraryPart.export(jdbc)));
		Instant dumpStarted = clock.instant();
		Manifest manifest;
		try {
			manifest = catalogLoad.export(file, notes);
		}
		catch (RuntimeException ex) {
			report.steps.put("dump", Duration.between(dumpStarted, clock.instant()));
			records.insertFailed(report.source, clock.instant(), report, "error", ex.toString());
			log.error("export failed, catalog_next dropped", ex);
			throw ex;
		}
		report.steps.put("dump", Duration.between(dumpStarted, clock.instant()));
		report.steps.put("export", Duration.between(started, clock.instant()));
		log.info("export {} ({} bytes), export_id {}, steps {}. Apply it on the production database, then finish here"
				+ " with the same file", file, Files.size(file), manifest.exportId(), ImportRecords.seconds(report.steps));
		return manifest;
	}

	/** Mode {@code apply}. */
	CatalogLoad.Result apply(Path file) {
		Instant started = clock.instant();
		Manifest manifest = catalogLoad.readManifest(file);
		Notes notes = notes(manifest);
		ImportReport report = notes.report();
		List<String> differences;
		try {
			differences = transactions.execute(status -> {
				LibraryPart.Applied part = LibraryPart.apply(notes.libraryPart(), libraryRegistry, jdbc);
				report.steps.put("library part", Duration.between(started, clock.instant()));
				List<String> found = DatabaseFingerprint.differences(DatabaseFingerprint.of(libraryCatalog, jdbc),
						catalogLoad.baseline(), notes.fingerprint(), manifest.baseline());
				if (found.isEmpty()) {
					log.info("library part: {} runs put in ({} libraries, {} links), {} here already", part.runs(),
							part.libraries(), part.links(), part.skippedRuns());
				}
				else {
					status.setRollbackOnly();
				}
				return found;
			});
		}
		catch (RuntimeException ex) { // a refused row, another library here, a broken payload
			report.steps.put("library part", Duration.between(started, clock.instant()));
			records.insertApplied(notes.source(), clock.instant(), report, manifest.exportId(), manifest.exportedAt(),
					"error", "library part: " + (ex instanceof DataAccessException data ? data.getMostSpecificCause() : ex));
			log.error("apply refused, the library part failed and was rolled back. Nothing was loaded", ex);
			throw ex;
		}
		report.steps.put("compare", Duration.between(started, clock.instant()));
		if (!differences.isEmpty()) {
			String detail = String.join("; ", differences);
			records.insertApplied(notes.source(), clock.instant(), report, manifest.exportId(), manifest.exportedAt(),
					"mismatch", detail);
			log.error("apply refused, this database differs from the file's ({}). Nothing was loaded", detail);
			throw new CatalogLoad.NotSwappedException("mismatch", "This database differs from the file's: " + detail);
		}
		CatalogLoad.Result result;
		try {
			result = catalogLoad.apply(file);
		}
		catch (RuntimeException ex) {
			report.steps.put("apply", Duration.between(started, clock.instant()));
			String reason = ex instanceof CatalogLoad.NotSwappedException notSwapped ? notSwapped.reason() : "error";
			records.insertApplied(notes.source(), clock.instant(), report, manifest.exportId(), manifest.exportedAt(),
					reason, ex.toString());
			log.error("apply failed ({}), the previous catalog stays", reason, ex);
			throw ex;
		}
		report.steps.putAll(result.steps());
		report.steps.put("apply", Duration.between(started, clock.instant()));
		records.insertApplied(notes.source(), clock.instant(), report, manifest.exportId(), manifest.exportedAt(), null,
				null);
		log.info("applied export {}: books {}, holdings {}, steps {}", manifest.exportId(), result.books(),
				result.holdings(), ImportRecords.seconds(report.steps));
		return result;
	}

	/** Mode {@code finish}. A failure keeps no row: the new tables keep waiting and the finish can run again. */
	CatalogLoad.Result finish(Path file) {
		Instant started = clock.instant();
		Manifest manifest = catalogLoad.readManifest(file);
		Notes notes = notes(manifest);
		CatalogLoad.Result result = catalogLoad.finishPrepared(file);
		ImportReport report = notes.report();
		report.steps.putAll(result.steps());
		report.steps.put("finish", Duration.between(started, clock.instant()));
		records.insertApplied(notes.source(), clock.instant(), report, manifest.exportId(), manifest.exportedAt(), null,
				null);
		log.info("finished export {}: books {}, holdings {}, steps {}", manifest.exportId(), result.books(),
				result.holdings(), ImportRecords.seconds(report.steps));
		return result;
	}

	/** Mode {@code discard}. */
	CatalogLoad.Discarded discard() {
		return catalogLoad.discardPrepared();
	}

	/** In-place imports and exports stop before reading anything when new tables are waiting (cycle 11 C6). */
	void requireNoWaitingExport() {
		if (catalogLoad.hasWaitingExport()) {
			throw new IllegalStateException("New tables of an export are waiting in catalog_next: first finish them"
					+ " (after the production apply) or discard them");
		}
	}

	private static Notes notes(Manifest manifest) {
		return JSON.readValue(manifest.notes(), Notes.class);
	}

}
