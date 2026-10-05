package com.chaekcheck.collection.internal.importer;

import java.sql.Timestamp;
import java.time.Instant;
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
		jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, candidate_library_count, skipped_by_reason, kept_book_id_count,
				  new_book_id_count, dropped_book_id_count)
				VALUES (:source, :importedAt, :books, :holdings, :skipped, :registered, :candidates,
				  CAST(:byReason AS jsonb), :kept, :added, :dropped)""")
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
			.update();
	}

	/** The reasons are fixed lower-case words, so no escaping is needed. */
	static String json(Map<String, Long> counts) {
		return counts.entrySet()
			.stream()
			.map(e -> "\"" + e.getKey() + "\": " + e.getValue())
			.collect(Collectors.joining(", ", "{", "}"));
	}

}
