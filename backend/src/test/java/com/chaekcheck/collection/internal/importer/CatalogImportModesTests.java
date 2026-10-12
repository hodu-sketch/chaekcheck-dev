package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.chaekcheck.TestPgCommand;
import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.Manifest;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlatformType;

/**
 * The separate import's modes (cycle 11 handoff step 5, ADR-0078) on a tiny SQLite, with the real pg tools in the
 * db-test container: export keeps no row, apply keeps one row with the PC's and its own steps, a database with
 * other libraries, links or catalog refuses the file ({@code mismatch}), waiting new tables stop new imports, and
 * the finish keeps a row with the same {@code export_id}. The pg command makes its own context; it is closed after.
 */
@SpringBootTest
@DirtiesContext
class CatalogImportModesTests {

	static final String KYOBO_BASE = "https://kyobo.test:446";

	@DynamicPropertySource
	static void pgCommand(DynamicPropertyRegistry registry) {
		registry.add("chaekcheck.catalog.load.pg-command", () -> String.join(",", TestPgCommand.get()));
	}

	@Autowired
	CatalogLoad catalogLoad;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@TempDir
	Path dir;

	LibraryCatalog libraryCatalog = mock(LibraryCatalog.class);

	Path sqlite;

	@BeforeEach
	void setUp() throws Exception {
		given(libraryCatalog.enabledLibraryRefs())
			.willReturn(List.of(new LibraryRef(1, KYOBO_BASE, PlatformType.KYOBO_V1)));
		sqlite = dir.resolve("test-modes.sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
			s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
					+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
					+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
					+ " detail_url TEXT)");
			s.execute("INSERT INTO library VALUES (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00')");
			s.execute("INSERT INTO holding (library_id, item_key, title, author_raw, publisher, content_type, detail_url,"
					+ " first_seen_at) VALUES"
					+ " (1, 'K1', '첫 책', '가', '나', 'EBOOK', '" + KYOBO_BASE + "/detail?id=K1', '2026-09-29T00:00:00+09:00'),"
					+ " (1, 'K2', '둘째 책', '다', '라', 'EBOOK', '" + KYOBO_BASE + "/detail?id=K2', '2026-09-29T00:00:00+09:00')");
		}
		empty();
	}

	@AfterEach
	void empty() {
		jdbc.sql("DROP SCHEMA IF EXISTS catalog_next CASCADE").update();
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
		jdbc.sql("DELETE FROM collection.catalog_import WHERE source LIKE 'test-%'").update();
	}

	private CatalogImporter importer() {
		return new CatalogImporter(libraryCatalog, () -> Map.of(1L, 1L), catalogLoad, new ImportRecords(jdbc),
				new NationalBibliographyStore(dataSource), Set.of(), clock());
	}

	private ImportTransfer transfer() {
		return new ImportTransfer(importer(), catalogLoad, libraryCatalog, jdbc, new ImportRecords(jdbc), clock());
	}

	private static Clock clock() {
		return Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);
	}

	@Test
	void exportKeepsNoRowAndApplyKeepsBothSteps() throws Exception { // AC-1, AC-7
		Path file = dir.resolve("export.dump");
		Manifest manifest = transfer().export(sqlite, null, file, "compact");
		assertThat(file).isRegularFile();
		assertThat(rows()).isEmpty();
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isZero();

		catalogLoad.discardPrepared();
		transfer().apply(file);

		Map<String, Object> row = jdbc.sql("""
				SELECT status, source, book_count, holding_count, export_id, step_seconds::text AS steps
				FROM collection.catalog_import WHERE source = 'test-modes.sqlite'""").query().singleRow();
		assertThat(row).containsEntry("status", "SUCCEEDED")
			.containsEntry("book_count", 2)
			.containsEntry("holding_count", 2)
			.containsEntry("export_id", manifest.exportId());
		assertThat(jdbc.sql("""
				SELECT jsonb_object_keys(step_seconds) FROM collection.catalog_import
				WHERE source = 'test-modes.sqlite'""").query(String.class).list()).containsExactlyInAnyOrder("read",
						"group", "nb", "normalize", "same_text", "books_copy", "holdings_read", "holdings_copy", "match",
						"write", "load", "compare", "restore", "index", "analyze", "swap", "apply");
		assertThat(jdbc.sql("SELECT title FROM catalog.book ORDER BY title").query(String.class).list())
			.containsExactly("둘째 책", "첫 책");
	}

	@Test
	void otherLibrariesAreAMismatch() throws Exception { // AC-4 (libraries)
		Path file = exportAndDiscard();
		given(libraryCatalog.enabledLibraryRefs())
			.willReturn(List.of(new LibraryRef(1, "https://kyobo.moved.test", PlatformType.KYOBO_V1)));

		assertMismatch(file, "도서관");
	}

	@Test
	void otherLinksAreAMismatch() throws Exception { // AC-4 (links)
		Path file = exportAndDiscard();
		Map<String, Object> link = jdbc.sql("""
				SELECT collection_library_id, library_id FROM collection.collection_link
				ORDER BY collection_library_id LIMIT 1""").query().singleRow();
		jdbc.sql("UPDATE collection.collection_link SET library_id = 999999 WHERE collection_library_id = :id")
			.param("id", link.get("collection_library_id"))
			.update();
		try {
			assertMismatch(file, "연결표");
		}
		finally {
			jdbc.sql("UPDATE collection.collection_link SET library_id = :library WHERE collection_library_id = :id")
				.param("library", link.get("library_id"))
				.param("id", link.get("collection_library_id"))
				.update();
		}
	}

	@Test
	void otherCatalogIsAMismatch() throws Exception { // AC-4 (book table)
		Path file = exportAndDiscard();
		jdbc.sql("""
				INSERT INTO catalog.book (title, author_display, publisher, title_norm, main_title_norm, publisher_norm,
				  content_type, library_ids)
				VALUES ('x', 'x', 'x', 'x', 'x', 'x', 'EBOOK', '{}')""").update();

		assertMismatch(file, "책 표");
	}

	@Test
	void waitingTablesStopInPlaceAndExport() throws Exception { // AC-12
		Path file = dir.resolve("export.dump");
		transfer().export(sqlite, null, file, "compact");

		assertThatThrownBy(() -> transfer().requireNoWaitingExport()).hasMessageContaining("first finish them");
		assertThatThrownBy(() -> transfer().export(sqlite, null, dir.resolve("again.dump"), "compact"))
			.hasMessageContaining("first finish them");
		assertThat(catalogLoad.hasWaitingExport()).isTrue();
	}

	@Test
	void finishKeepsARowWithTheExportId() throws Exception {
		Path file = dir.resolve("export.dump");
		Manifest manifest = transfer().export(sqlite, null, file, "compact");

		transfer().finish(file);

		Map<String, Object> row = jdbc.sql("""
				SELECT status, export_id FROM collection.catalog_import WHERE source = 'test-modes.sqlite'""")
			.query()
			.singleRow();
		assertThat(row).containsEntry("status", "SUCCEEDED").containsEntry("export_id", manifest.exportId());
		assertThat(jdbc.sql("""
				SELECT step_seconds -> 'finish' IS NOT NULL AND step_seconds -> 'swap' IS NOT NULL FROM collection.catalog_import
				WHERE source = 'test-modes.sqlite'""").query(Boolean.class).single()).isTrue();
		assertThat(catalogLoad.hasWaitingExport()).isFalse();
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(2);
	}

	private Path exportAndDiscard() throws Exception {
		Path file = dir.resolve("export.dump");
		transfer().export(sqlite, null, file, "compact");
		catalogLoad.discardPrepared();
		return file;
	}

	private void assertMismatch(Path file, String what) {
		long books = jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single();
		assertThatThrownBy(() -> transfer().apply(file)).isInstanceOfSatisfying(CatalogLoad.NotSwappedException.class,
				ex -> assertThat(ex.reason()).isEqualTo("mismatch"));
		Map<String, Object> row = jdbc.sql("""
				SELECT status, failure_reason, failure_detail, export_id IS NOT NULL AS linked
				FROM collection.catalog_import WHERE source = 'test-modes.sqlite'""").query().singleRow();
		assertThat(row).containsEntry("status", "FAILED")
			.containsEntry("failure_reason", "mismatch")
			.containsEntry("linked", true);
		assertThat((String) row.get("failure_detail")).contains(what);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(books);
		assertThat(catalogLoad.hasWaitingExport()).isFalse();
	}

	private List<Map<String, Object>> rows() {
		return jdbc.sql("SELECT * FROM collection.catalog_import WHERE source LIKE 'test-%'").query().listOfRows();
	}

}
