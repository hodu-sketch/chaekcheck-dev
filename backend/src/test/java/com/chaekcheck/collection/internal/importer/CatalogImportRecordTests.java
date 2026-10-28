package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The cycle 11 columns of {@code collection.catalog_import} (handoff step 2): {@code export_id} and
 * {@code exported_at} come together or not at all, and a {@code mismatch} row is kept.
 */
@SpringBootTest
class CatalogImportRecordTests {

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void empty() {
		jdbc.sql("DELETE FROM collection.catalog_import WHERE source LIKE 'test-%'").update();
	}

	@Test
	void appliedRowKeepsTheExportAndBothSteps() {
		ImportReport report = new ImportReport();
		report.books = 3;
		report.holdings = 5;
		report.steps.put("read", Duration.ofSeconds(2));
		report.steps.put("restore", Duration.ofMillis(1500));
		UUID exportId = UUID.randomUUID();
		Instant exportedAt = Instant.parse("2026-10-12T01:00:00Z");

		new ImportRecords(jdbc).insertApplied("test-a.sqlite", Instant.now(), report, exportId, exportedAt, null, null);

		Map<String, Object> row = jdbc.sql("""
				SELECT status, failure_reason, export_id, exported_at, step_seconds::text AS steps, book_count
				FROM collection.catalog_import WHERE source = 'test-a.sqlite'""").query().singleRow();
		assertThat(row).containsEntry("status", "SUCCEEDED")
			.containsEntry("export_id", exportId)
			.containsEntry("book_count", 3);
		assertThat(row.get("failure_reason")).isNull();
		assertThat(((java.sql.Timestamp) row.get("exported_at")).toInstant()).isEqualTo(exportedAt);
		assertThat((String) row.get("steps")).isEqualTo("{\"read\": 2.0, \"restore\": 1.5}");
	}

	@Test
	void mismatchRowIsFailed() {
		new ImportRecords(jdbc).insertApplied("test-m.sqlite", Instant.now(), new ImportReport(), UUID.randomUUID(),
				Instant.now(), "mismatch", "도서관: 이 DB 12개 / 파일 13개");

		Map<String, Object> row = jdbc.sql("""
				SELECT status, failure_reason, failure_detail FROM collection.catalog_import
				WHERE source = 'test-m.sqlite'""").query().singleRow();
		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "mismatch")
			.containsEntry("failure_detail", "도서관: 이 DB 12개 / 파일 13개");
	}

	@Test
	void inPlaceRowHasNoExport() {
		new ImportRecords(jdbc).insert("test-i.sqlite", Instant.now(), new ImportReport());

		Map<String, Object> row = jdbc.sql("""
				SELECT export_id, exported_at FROM collection.catalog_import WHERE source = 'test-i.sqlite'""")
			.query()
			.singleRow();
		assertThat(row.get("export_id")).isNull();
		assertThat(row.get("exported_at")).isNull();
	}

	@Test
	void deletedDescriptionsAreKeptOnSucceededRowsOnly() { // cycle 26 step 4
		ImportReport report = new ImportReport();
		report.descriptionsDeleted = 7L;
		ImportRecords records = new ImportRecords(jdbc);
		records.insert("test-d.sqlite", Instant.now(), report);
		records.insertFailed("test-f.sqlite", Instant.now(), report, "error", "x");
		records.insert("test-n.sqlite", Instant.now(), new ImportReport());

		assertThat(jdbc.sql("""
				SELECT source || '=' || coalesce(description_deleted_count::text, 'null') FROM collection.catalog_import
				WHERE source IN ('test-d.sqlite', 'test-f.sqlite', 'test-n.sqlite') ORDER BY source""")
			.query(String.class)
			.list()).containsExactly("test-d.sqlite=7", "test-f.sqlite=null", "test-n.sqlite=null");
	}

	@Test
	void exportIdWithoutTimeIsRefused() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, other_library_count, skipped_by_reason, export_id)
				VALUES ('test-x.sqlite', now(), 0, 0, 0, 0, 0, '{}', gen_random_uuid())""").update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("catalog_import_export_check");
	}

}
