package com.chaekcheck.collection.internal.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.foryou.ForYouTransfer;

/**
 * The {@code neighbours} mode against db-test (cycle 23 step 3-3): the file's export id is checked against the latest
 * succeeded catalog import (a later failed one or an in-place one without an id counts as described), a different id
 * is refused before anything is written unless forced, and {@code add} upserts the file's rows with the run's day. The
 * import rows live in the test transaction (another connection never sees them); the neighbours are written by
 * {@link ForYouTransfer} on its own connection, so this test's books (930201-930203) are deleted after each test.
 * {@code full} is not run here: it swaps the whole table, which {@code ForYouTransferTests} covers.
 */
@SpringBootTest
@Transactional
class NeighboursImportDbTests {

	static final UUID EXPORT = UUID.fromString("11111111-2222-3333-4444-555555555555");

	static final Clock DAY = Clock.fixed(Instant.parse("2026-10-25T01:00:00Z"), ZoneId.of("Asia/Seoul"));

	@TempDir
	Path dir;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ForYouTransfer forYou;

	@AfterTransaction
	void deleteTheBooks() {
		// the neighbours were committed on the transfer's own connection; after the rollback, so the delete stays
		jdbc.sql("DELETE FROM foryou.book_neighbour WHERE book_id BETWEEN 930201 AND 930203").update();
	}

	@Test
	void theLatestSucceededImportsExportIdLetsTheFileIn() throws Exception {
		importRow("2099-01-01T00:00:00Z", EXPORT, "SUCCEEDED");
		// a later failed import does not count
		importRow("2099-01-02T00:00:00Z", UUID.randomUUID(), "FAILED");

		NeighboursImport.Report report = command().run(file(EXPORT, "930201\t930202,930203", "930202\t930201"), "add",
				false);

		assertThat(report.rows()).isEqualTo(2);
		assertThat(report.forced()).isFalse();
		assertThat(neighbours(930_201)).isEqualTo("{930202,930203}:2026-10-25");
		assertThat(neighbours(930_202)).isEqualTo("{930201}:2026-10-25");

		command().run(file(EXPORT, "930202\t930203,930201"), "add", false);

		assertThat(neighbours(930_202)).isEqualTo("{930203,930201}:2026-10-25");
		assertThat(neighbours(930_201)).isEqualTo("{930202,930203}:2026-10-25");
	}

	@Test
	void anotherExportIdIsRefusedUnlessForced() throws Exception {
		importRow("2099-01-01T00:00:00Z", EXPORT, "SUCCEEDED");
		UUID other = UUID.fromString("99999999-2222-3333-4444-555555555555");
		Path file = file(other, "930201\t930202");

		assertThatThrownBy(() -> command().run(file, "add", false)).isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining(other.toString())
			.hasMessageContaining(EXPORT.toString())
			.hasMessageContaining("nothing put in");
		assertThat(neighbours(930_201)).isNull();

		NeighboursImport.Report forced = command().run(file, "add", true);

		assertThat(forced.forced()).isTrue();
		assertThat(neighbours(930_201)).isEqualTo("{930202}:2026-10-25");
	}

	@Test
	void anInPlaceImportHasNoExportIdSoTheFileNeedsForce() throws Exception {
		importRow("2099-01-01T00:00:00Z", null, "SUCCEEDED");

		assertThat(command().latestExportId()).isEmpty();
		assertThatThrownBy(() -> command().run(file(EXPORT, "930201\t930202"), "add", false))
			.isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("export null");
	}

	@Test
	void aBrokenRowRefusesTheFileBeforeAnythingIsWritten() throws Exception {
		importRow("2099-01-01T00:00:00Z", EXPORT, "SUCCEEDED");

		assertThatThrownBy(() -> command().run(file(EXPORT, "930201\t930202", "930203\t930203"), "add", false))
			.isInstanceOf(NeighboursFile.RefusedException.class)
			.hasMessageContaining("row 3: book 930203 is its own neighbour");
		assertThat(neighbours(930_201)).isNull();
	}

	private NeighboursImport command() {
		return new NeighboursImport(jdbc, forYou, DAY);
	}

	private String neighbours(long bookId) {
		return jdbc.sql("SELECT neighbour_ids::text || ':' || computed_on FROM foryou.book_neighbour WHERE book_id = ?")
			.param(bookId)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/** An import row far after the real ones, so it is the latest. */
	private void importRow(String importedAt, UUID exportId, String status) {
		jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, other_library_count, skipped_by_reason, export_id, exported_at, status,
				  failure_reason)
				VALUES ('test-neighbours', CAST(:at AS timestamptz), 0, 0, 0, 0, 0, '{}', :exportId,
				  CASE WHEN CAST(:exportId AS uuid) IS NULL THEN NULL ELSE now() END, :status,
				  CASE WHEN :status = 'FAILED' THEN 'error' END)""")
			.param("at", importedAt)
			.param("exportId", exportId)
			.param("status", status)
			.update();
	}

	private Path file(UUID exportId, String... rows) throws Exception {
		Path file = Files.createTempFile(dir, "neighbours", ".tsv");
		Files.writeString(file, "# export_id=" + exportId + "\n" + String.join("\n", List.of(rows)) + "\n",
				StandardCharsets.UTF_8);
		return file;
	}

}
