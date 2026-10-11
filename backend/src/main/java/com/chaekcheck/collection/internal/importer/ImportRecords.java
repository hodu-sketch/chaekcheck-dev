package com.chaekcheck.collection.internal.importer;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;

/** One row of {@code collection.catalog_import} per import (AC-11); rows are kept as the import history. */
final class ImportRecords {

	private final JdbcClient jdbc;

	ImportRecords(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(String source, Instant importedAt, ImportReport report) {
		insert(source, importedAt, report, null, null);
	}

	/**
	 * A load that failed before the swap (cycle 08 handoff step 4-7): the previous catalog stays.
	 * @param reason {@code lock_not_acquired}, {@code outside_foreign_key} or {@code error}
	 */
	void insertFailed(String source, Instant importedAt, ImportReport report, String reason, String detail) {
		insert(source, importedAt, report, reason, detail == null ? "" : detail);
	}

	private void insert(String source, Instant importedAt, ImportReport report, String failureReason,
			String failureDetail) {
		jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, candidate_library_count, skipped_by_reason, kept_book_id_count,
				  new_book_id_count, dropped_book_id_count, collected_from, collected_to, step_seconds, status,
				  failure_reason, failure_detail)
				VALUES (:source, :importedAt, :books, :holdings, :skipped, :registered, :candidates,
				  CAST(:byReason AS jsonb), :kept, :added, :dropped, :collectedFrom, :collectedTo,
				  CAST(:stepSeconds AS jsonb), :status, :failureReason, :failureDetail)""")
			.param("source", source)
			.param("importedAt", Timestamp.from(importedAt))
			.param("books", report.books)
			.param("holdings", report.holdings)
			.param("skipped", report.skippedCount())
			.param("registered", report.registeredLibraries)
			.param("candidates", report.candidateLibraries)
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
