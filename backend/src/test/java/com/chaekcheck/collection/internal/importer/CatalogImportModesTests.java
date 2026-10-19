package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
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
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.Manifest;
import com.chaekcheck.collection.internal.libraryinsert.LibraryPart;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.LibraryRegistry;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.picks.CuratedListTransfer;

/**
 * The separate import's modes (cycle 11 handoff step 5, ADR-0078) on a tiny SQLite, with the real pg tools in the
 * db-test container: export keeps no row, apply keeps one row with the PC's and its own steps, a database with
 * other libraries, links or catalog refuses the file ({@code mismatch}), waiting new tables stop new imports, and
 * the finish keeps a row with the same {@code export_id}. The pg command makes its own context; it is closed after.
 * Since cycle 21 (ADR-0123) the file carries the recommended lists, put in after the swap (handoff step 5).
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

	@Autowired
	CuratedListTransfer curatedLists;

	@Autowired
	BookLookup bookLookup;

	/** One Lee Dong-jin row the export matches to "첫 책" by title and first author, one SNU row it cannot match. */
	Path lists;

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
		lists = Files.createDirectories(dir.resolve("lists"));
		Files.writeString(lists.resolve("fields.tsv"), tsv(List.of("list", "code", "name", "position"),
				List.of("leedongjin", "f", "분야", "1"), List.of("snu100", "k", "한국문학", "1"),
				List.of("kaist", "ai", "AI", "1"), List.of("nobel", "ancient", "고대·중세", "1")), StandardCharsets.UTF_8);
		Files.writeString(lists.resolve("leedongjin.tsv"), tsv(
				List.of("month", "position", "year_book", "video_id", "field", "title", "author", "publisher", "isbn13",
						"cover_url", "pinned_title", "pinned_author", "pinned_publisher", "pinned_isbn13", "pinned_by",
						"checked_on"),
				List.of("2026-06", "1", "", "lMx0zEd1qtY", "f", "첫 책", "가", "", "", "", "", "", "", "", "", "")),
				StandardCharsets.UTF_8);
		Files.writeString(lists.resolve("snu100.tsv"), tsv(
				List.of("toc_no", "field", "title", "author", "cover_url", "pinned_title", "pinned_author",
						"pinned_publisher", "pinned_isbn13", "pinned_by"),
				List.of("17", "k", "광장", "최인훈", "", "", "", "", "", "")), StandardCharsets.UTF_8);
		// KAIST (user 2026-10-19): no rows unless a test writes them
		Files.writeString(lists.resolve("kaist.tsv"), tsv(KAIST_HEADER), StandardCharsets.UTF_8);
		// the Bokklubben World Library (user 2026-10-20): no rows unless a test writes them
		Files.writeString(lists.resolve("nobel.tsv"), tsv(NOBEL_HEADER), StandardCharsets.UTF_8);
		empty();
	}

	static final List<String> KAIST_HEADER = List.of("year", "field", "position", "title", "author", "publisher", "isbn13",
			"cover_url", "pinned_title", "pinned_author", "pinned_publisher", "pinned_isbn13", "pinned_by", "note");

	static final List<String> NOBEL_HEADER = List.of("field", "position", "title", "author", "publisher", "isbn13",
			"cover_url", "pinned_title", "pinned_author", "pinned_publisher", "pinned_isbn13", "pinned_by", "note");

	@SafeVarargs
	private static String tsv(List<String>... lines) {
		StringBuilder text = new StringBuilder();
		for (List<String> line : lines) {
			text.append(String.join("\t", line)).append('\n');
		}
		return text.toString();
	}

	@AfterEach
	void empty() {
		jdbc.sql("DROP SCHEMA IF EXISTS catalog_next CASCADE").update();
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
		jdbc.sql("DELETE FROM collection.catalog_import WHERE source LIKE 'test-%'").update();
		jdbc.sql("DELETE FROM picks.list_field WHERE list_key IN ('leedongjin', 'snu100', 'kaist', 'nobel')").update();
		removeInsertedLibrary();
	}

	private CatalogImporter importer() {
		return new CatalogImporter(libraryCatalog, () -> Map.of(1L, 1L), catalogLoad, new ImportRecords(jdbc),
				new NationalBibliographyStore(dataSource), Set.of(), clock());
	}

	private ImportTransfer transfer() {
		return transfer(curatedLists);
	}

	private ImportTransfer transfer(CuratedListTransfer lists) {
		return new ImportTransfer(importer(), catalogLoad, libraryCatalog, libraryRegistry, jdbc, transactionManager,
				new ImportRecords(jdbc), clock(), lists, this.lists);
	}

	/** {@code list_key:title:book title or -} of the stored Lee Dong-jin and SNU rows. */
	private List<String> storedLists() {
		return jdbc.sql("""
				SELECT l.list_key || ':' || l.title || ':' || coalesce(b.title, '-')
				FROM picks.list_book l LEFT JOIN catalog.book b ON b.id = l.book_id
				WHERE l.list_key IN ('leedongjin', 'snu100') ORDER BY l.list_key""").query(String.class).list();
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
						"write", "load", "library part", "compare", "restore", "index", "analyze", "swap", "apply",
						"lists");
		assertThat(jdbc.sql("SELECT title FROM catalog.book ORDER BY title").query(String.class).list())
			.containsExactly("둘째 책", "첫 책");
		// cycle 21: matched to the file's book, put in after the swap; the ids carried over, so the id still fits
		assertThat(storedLists()).containsExactly("leedongjin:첫 책:첫 책", "snu100:광장:-");
	}

	@Test
	void theFileCarriesTheListsMatchedToItsBooks() throws Exception { // cycle 21 (ADR-0123, ADR-0124)
		Path file = dir.resolve("export.dump");
		transfer().export(sqlite, null, file, "compact");

		String notes = catalogLoad.readManifest(file).notes();
		assertThat(notes).contains("\"listPart\"", "lMx0zEd1qtY", "광장", "matched");
		assertThat(catalogLoad.readManifest(file).formatVersion()).isEqualTo(3);
		assertThat(storedLists()).isEmpty();
	}

	@Test
	void noListsFolderStopsTheExportBeforeAnythingIsRead() throws Exception { // cycle 21 (ADR-0123)
		Files.delete(lists.resolve("snu100.tsv"));

		assertThatThrownBy(() -> transfer().export(sqlite, null, dir.resolve("export.dump"), "compact"))
			.isInstanceOf(CuratedListTransfer.ListFileException.class)
			.hasMessageContaining("snu100.tsv");
		assertThat(jdbc.sql("SELECT to_regclass('catalog_next.book') IS NULL").query(Boolean.class).single()).isTrue();
		assertThat(dir.resolve("export.dump")).doesNotExist();
	}

	@Test
	void listsThatFailLeaveTheSwappedCatalogAndTheListsBefore() throws Exception { // cycle 21 handoff step 5-3
		Path file = exportAndDiscard();
		CuratedListTransfer failing = mock(CuratedListTransfer.class);
		given(failing.replace(any())).willThrow(new IllegalStateException("lock_timeout"));

		transfer(failing).apply(file);

		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("""
				SELECT status || ':' || jsonb_exists(step_seconds, 'lists failed') || ':' || jsonb_exists(step_seconds, 'apply')
				FROM collection.catalog_import WHERE source = 'test-modes.sqlite'""").query(String.class).single())
			.isEqualTo("SUCCEEDED:true:true");
		assertThat(storedLists()).isEmpty();
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
		assertThat(storedLists()).containsExactly("leedongjin:첫 책:첫 책", "snu100:광장:-");
	}

	/** Mode lists (user 2026-10-19): the lists matched to the catalog here and put in; no catalog change, no row. */
	@Test
	void listsModePutsInOnlyTheListsMatchedToTheLiveCatalog() throws Exception {
		transfer().finish(exportAndKeep());
		long imports = jdbc.sql("SELECT count(*) FROM collection.catalog_import").query(Long.class).single();
		List<Long> books = jdbc.sql("SELECT id FROM catalog.book ORDER BY id").query(Long.class).list();
		Files.writeString(lists.resolve("kaist.tsv"), tsv(KAIST_HEADER,
				List.of("2026", "ai", "2", "없는 책", "누구", "", "", "", "", "", "", "", "", ""),
				List.of("2026", "ai", "1", "둘째 책", "다", "라", "", "", "", "", "", "", "", "")), StandardCharsets.UTF_8);
		Files.writeString(lists.resolve("snu100.tsv"), Files.readString(lists.resolve("snu100.tsv"))
				+ tsv(List.of("18", "k", "첫 책", "가", "", "", "", "", "", "")), StandardCharsets.UTF_8);
		Files.writeString(lists.resolve("nobel.tsv"), tsv(NOBEL_HEADER,
				List.of("ancient", "1", "첫 책", "가", "", "", "", "", "", "", "", "", "Bokklubben")), StandardCharsets.UTF_8);

		CuratedListTransfer.Replaced replaced = transfer().listsOnly(bookLookup);

		assertThat(replaced.books()).isEqualTo(6);
		assertThat(jdbc.sql("""
				SELECT l.field_code || ':' || l.position || ':' || coalesce(b.title, '-')
				FROM picks.list_book l LEFT JOIN catalog.book b ON b.id = l.book_id
				WHERE l.list_key = 'nobel'""").query(String.class).list())
			.containsExactly("ancient:1:첫 책");
		assertThat(jdbc.sql("""
				SELECT l.field_code || ':' || l.position || ':' || l.title || ':' || coalesce(b.title, '-')
				FROM picks.list_book l LEFT JOIN catalog.book b ON b.id = l.book_id
				WHERE l.list_key = 'kaist' ORDER BY l.position""").query(String.class).list())
			.containsExactly("ai:1:둘째 책:둘째 책", "ai:2:없는 책:-");
		assertThat(storedLists()).containsExactlyInAnyOrder("leedongjin:첫 책:첫 책", "snu100:광장:-",
				"snu100:첫 책:첫 책");
		assertThat(jdbc.sql("SELECT id FROM catalog.book ORDER BY id").query(Long.class).list()).isEqualTo(books);
		assertThat(jdbc.sql("SELECT count(*) FROM collection.catalog_import").query(Long.class).single())
			.isEqualTo(imports);
	}

	@Test
	void listsModeWithAWrongFileStopsAndKeepsTheListsBefore() throws Exception { // user 2026-10-19
		transfer().finish(exportAndKeep());
		Files.writeString(lists.resolve("kaist.tsv"), tsv(KAIST_HEADER,
				List.of("2026", "ai", "1", "가", "", "", "", "", "", "", "", "", "", ""),
				List.of("2026", "ai", "1", "나", "", "", "", "", "", "", "", "", "", "")), StandardCharsets.UTF_8);

		assertThatThrownBy(() -> transfer().listsOnly(bookLookup))
			.isInstanceOf(CuratedListTransfer.ListFileException.class)
			.hasMessageContaining("kaist.tsv line 3");
		assertThat(storedLists()).containsExactly("leedongjin:첫 책:첫 책", "snu100:광장:-");
	}

	private Path exportAndKeep() throws Exception {
		Path file = dir.resolve("export.dump");
		transfer().export(sqlite, null, file, "compact");
		return file;
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
