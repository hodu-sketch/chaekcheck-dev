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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.TestPgCommand;
import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.Manifest;
import com.chaekcheck.collection.internal.libraryinsert.LibraryPart;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.LibraryRegistry;
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

	@Autowired
	LibraryRegistry libraryRegistry;

	@Autowired
	PlatformTransactionManager transactionManager;

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
		removeInsertedLibrary();
	}

	private CatalogImporter importer() {
		return new CatalogImporter(libraryCatalog, () -> Map.of(1L, 1L), catalogLoad, new ImportRecords(jdbc),
				new NationalBibliographyStore(dataSource), Set.of(), clock());
	}

	private ImportTransfer transfer() {
		return new ImportTransfer(importer(), catalogLoad, libraryCatalog, libraryRegistry, jdbc, transactionManager,
				new ImportRecords(jdbc), clock());
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
						"write", "load", "library part", "compare", "restore", "index", "analyze", "swap", "apply");
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

	/** Cycle 13 (ADR-0086, AC-7): the export carries the succeeded library-insert runs, the apply puts them in. */
	@Test
	void theLibraryPartTravelsWithTheFile() throws Exception {
		long id = insertedLibrary();
		Path file = dir.resolve("export.dump");
		Manifest manifest = transfer().export(sqlite, null, file, "compact");
		assertThat(catalogLoad.readManifest(file).notes()).contains("\"libraryPart\"", "aaaaaaaaaaaa", PART_URL);
		String ranAt = jdbc.sql("SELECT ran_at::text FROM collection.library_insert_run").query(String.class).single();
		catalogLoad.discardPrepared();
		removeInsertedLibrary(); // the production database has not got it yet

		transfer().apply(file);

		assertThat(jdbc.sql("""
				SELECT l.base_url, k.collection_library_id, r.inserted_count, r.join_surveyed_on::text, r.ran_at::text
				FROM library.library l JOIN collection.collection_link k ON k.library_id = l.id
				  JOIN collection.library_insert_run r ON r.report_id = 'aaaaaaaaaaaa' WHERE l.id = :id""")
			.param("id", id)
			.query((rs, n) -> List.of(rs.getString(1), rs.getLong(2), rs.getInt(3), rs.getString(4), rs.getString(5)))
			.single()).containsExactly(PART_URL, 910001L, 1, "2026-10-05", ranAt);
		assertThat(jdbc.sql("SELECT count(*) FROM library.eligibility_rule WHERE library_id = :id")
			.param("id", id)
			.query(Long.class)
			.single()).isEqualTo(1);
		assertThat(jdbc.sql("""
				SELECT status, export_id FROM collection.catalog_import WHERE source = 'test-modes.sqlite'""")
			.query()
			.singleRow()).containsEntry("status", "SUCCEEDED").containsEntry("export_id", manifest.exportId());
	}

	/** A library part already here is skipped, so an apply can run again (ADR-0086). */
	@Test
	void aLibraryPartHereIsSkipped() {
		insertedLibrary();
		List<LibraryPart.Run> runs = LibraryPart.export(jdbc);

		LibraryPart.Applied applied = new TransactionTemplate(transactionManager)
			.execute(status -> LibraryPart.apply(runs, libraryRegistry, jdbc));

		assertThat(applied).isEqualTo(new LibraryPart.Applied(0, 1, 0, 0));
	}

	@Test
	void aFailingLibraryPartStartsNothing() throws Exception {
		long id = insertedLibrary();
		Path file = exportAndDiscard();
		removeInsertedLibrary();
		// another library holds the address now: base_url is UNIQUE
		jdbc.sql("""
				INSERT INTO library.library (id, name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES (:id, '다른 곳', 'YES24_V2', 'YES24', 'OWNED', :url, 'test')""")
			.param("id", id + 1)
			.param("url", PART_URL)
			.update();

		assertThatThrownBy(() -> transfer().apply(file))
			.isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

		Map<String, Object> row = jdbc.sql("""
				SELECT status, failure_reason, failure_detail FROM collection.catalog_import
				WHERE source = 'test-modes.sqlite'""").query().singleRow();
		assertThat(row).containsEntry("status", "FAILED").containsEntry("failure_reason", "error");
		assertThat((String) row.get("failure_detail")).startsWith("library part");
		assertThat(jdbc.sql("SELECT count(*) FROM collection.library_insert_run").query(Long.class).single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isZero();
		assertThat(catalogLoad.hasWaitingExport()).isFalse();
	}

	/** Review 2026-10-12: an id taken by another library here stops the library part instead of skipping it. */
	@Test
	void anotherLibraryWithTheSameIdStartsNothing() throws Exception {
		long id = insertedLibrary();
		Path file = exportAndDiscard();
		removeInsertedLibrary();
		jdbc.sql("""
				INSERT INTO library.library (id, name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES (:id, '다른 곳', 'YES24_V2', 'YES24', 'OWNED', 'https://other-part.test', 'test')""")
			.param("id", id)
			.update();
		try {
			assertThatThrownBy(() -> transfer().apply(file)).isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("library " + id);

			assertThat(jdbc.sql("""
					SELECT failure_reason, failure_detail LIKE 'library part%' AS part FROM collection.catalog_import
					WHERE source = 'test-modes.sqlite'""").query().singleRow()).containsEntry("failure_reason", "error")
				.containsEntry("part", true);
			assertThat(jdbc.sql("SELECT count(*) FROM collection.collection_link WHERE collection_library_id >= 900000")
				.query(Long.class)
				.single()).isZero();
			assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isZero();
		}
		finally {
			jdbc.sql("DELETE FROM library.library WHERE id = :id").param("id", id).update();
		}
	}

	@Test
	void aMismatchRollsTheLibraryPartBack() throws Exception {
		long id = insertedLibrary();
		Path file = exportAndDiscard();
		removeInsertedLibrary();
		given(libraryCatalog.enabledLibraryRefs())
			.willReturn(List.of(new LibraryRef(1, "https://kyobo.moved.test", PlatformType.KYOBO_V1)));

		assertMismatch(file, "도서관");

		assertThat(jdbc.sql("SELECT count(*) FROM library.library WHERE id = :id")
			.param("id", id)
			.query(Long.class)
			.single()).isZero();
		assertThat(jdbc.sql("SELECT count(*) FROM collection.library_insert_run").query(Long.class).single()).isZero();
	}

	static final String PART_URL = "https://part.test";

	/** A library as the library-insert command leaves it: library, rule, link and a succeeded run row. */
	private long insertedLibrary() {
		long id = libraryRegistry.nextId();
		String entry = """
				{"collectionId":910001,"library":{"id":%d,"name":"반영 시험","platformType":"YES24_V2",
				"vendor":"YES24","lendingModel":"OWNED","orderNo":1,"baseUrl":"%s","homepageUrl":"%s",
				"visitRequired":false,"deferred":false,"joinSurvey":"MEMBERS_ONLY",
				"rules":[{"basis":"RESIDENCE","scopeType":"REGION","scopeRegionId":11}]}}""".formatted(id, PART_URL,
				PART_URL);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			jdbc.sql("""
					INSERT INTO collection.library_insert_run
					  (report_id, ran_at, snapshot, inserted_count, join_surveyed_on, status, payload)
					VALUES ('aaaaaaaaaaaa', TIMESTAMPTZ '2026-10-12 10:00:00+09', 'copy.sqlite', 1, DATE '2026-10-05',
					  'SUCCEEDED', CAST(:payload AS jsonb))""")
				.param("payload", "{\"newLibraries\":[" + entry + "]}")
				.update();
			LibraryPart.Run run = LibraryPart.export(jdbc).getFirst();
			jdbc.sql("DELETE FROM collection.library_insert_run").update();
			LibraryPart.apply(List.of(run), libraryRegistry, jdbc);
		});
		return id;
	}

	private void removeInsertedLibrary() {
		jdbc.sql("DELETE FROM collection.library_insert_run").update();
		jdbc.sql("DELETE FROM library.library WHERE base_url = :url").param("url", PART_URL).update();
		jdbc.sql("DELETE FROM collection.collection_link WHERE collection_library_id >= 900000").update();
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
