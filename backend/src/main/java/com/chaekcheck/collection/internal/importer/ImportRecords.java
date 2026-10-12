package com.chaekcheck.collection.internal.importer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

/** One row of {@code collection.catalog_import} per import (AC-11); rows are kept as the import history. */
final class ImportRecords {

	private final JdbcClient jdbc;

	ImportRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String source, Instant importedAt, ImportReport report) {
		insert(source, importedAt, report, null, null, null, null);
	}

	/**
	 * A load that failed before the swap (cycle 08 handoff step 4-7): the previous catalog stays.
	 * @param reason {@code lock_not_acquired}, {@code outside_foreign_key}, {@code mismatch} (the two databases
	 * differ, cycle 11) or {@code error}
	 */
	void insertFailed(String source, Instant importedAt, ImportReport report, String reason, String detail) {
		insert(source, importedAt, report, reason, detail == null ? "" : detail, null, null);
	}

	/**
	 * A load of a file made on another database (cycle 11, ADR-0078): the apply on the production database, or the
	 * finish on the PC. {@code report} holds the PC import's counts and steps followed by this database's steps; the
	 * rows of one file share {@code export_id}.
	 * @param reason null when it succeeded, else as {@link #insertFailed}
	 */
	void insertApplied(String source, Instant importedAt, ImportReport report, UUID exportId, Instant exportedAt,
			String reason, String detail) {
		insert(source, importedAt, report, reason, reason == null ? null : detail == null ? "" : detail, exportId,
				exportedAt);
	}

	private void insert(String source, Instant importedAt, ImportReport report, String failureReason,
			String failureDetail, UUID exportId, Instant exportedAt) {
		jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, other_library_count, skipped_by_reason, kept_book_id_count,
				  new_book_id_count, dropped_book_id_count, collected_from, collected_to, step_seconds, status,
				  failure_reason, failure_detail, export_id, exported_at)
				VALUES (:source, :importedAt, :books, :holdings, :skipped, :registered, :others,
				  CAST(:byReason AS jsonb), :kept, :added, :dropped, :collectedFrom, :collectedTo,
				  CAST(:stepSeconds AS jsonb), :status, :failureReason, :failureDetail, :exportId, :exportedAt)""")
			.param("source", source)
			.param("importedAt", Timestamp.from(importedAt))
			.param("books", report.books)
			.param("holdings", report.holdings)
			.param("skipped", report.skippedCount())
			.param("registered", report.registeredLibraries)
			.param("others", report.otherLibraries)
			.param("byReason", json(report.skipped))
			.param("kept", report.keptBookIds)
			.param("added", report.newBookIds)
			.param("dropped", report.droppedBookIds)
			.param("collectedFrom", timestamp(report.collectedFrom))
			.param("collectedTo", timestamp(report.collectedTo))
			.param("stepSeconds", seconds(report.steps))
			.param("status", failureReason == null ? "SUCCEEDED" : "FAILED")
			.param("failureReason", failureReason)
			.param("failureDetail", failureDetail == null ? null
					: failureDetail.substring(0, Math.min(failureDetail.length(), 1000)))
			.param("exportId", exportId)
			.param("exportedAt", timestamp(exportedAt))
			.update();
	}

	private static Timestamp timestamp(Instant instant) {
		return instant == null ? null : Timestamp.from(instant);
	}

	/** {"read": 412.3, ...} in the steps' order; the keys are fixed lower-case words. */
	static String seconds(Map<String, Duration> steps) {
		return steps.entrySet()
			.stream()
			.map(e -> "\"" + e.getKey() + "\": " + String.format(Locale.ROOT, "%.1f", e.getValue().toMillis() / 1000.0))
			.collect(Collectors.joining(", ", "{", "}"));
	}

	/** The reasons are fixed lower-case words, so no escaping is needed. */
	static String json(Map<String, Long> counts) {
		return counts.entrySet()
			.stream()
			.map(e -> "\"" + e.getKey() + "\": " + e.getValue())
			.collect(Collectors.joining(", ", "{", "}"));
	}

}
