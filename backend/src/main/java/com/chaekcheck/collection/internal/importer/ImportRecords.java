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
				  registered_library_count, candidate_library_count, skipped_by_reason)
				VALUES (:source, :importedAt, :books, :holdings, :skipped, :registered, :candidates,
				  CAST(:byReason AS jsonb))""")
			.param("source", source)
			.param("importedAt", Timestamp.from(importedAt))
			.param("books", report.books)
			.param("holdings", report.holdings)
			.param("skipped", report.skippedCount())
			.param("registered", report.registeredLibraries)
			.param("candidates", report.candidateLibraries)
			.param("byReason", json(report.skipped))
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
