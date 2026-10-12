package com.chaekcheck.collection.internal.libraryinsert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.LibraryRegistry;

/**
 * Plan cycle 13 handoff step 5: the library-insert command on db-test with a small collection DB copy (AC-3, AC-4,
 * AC-5, AC-8, AC-12). The command commits, so every test removes what it put in. Collection ids start at 900001 so
 * they never meet the seed's links; the seed's linked libraries are not in the copy and show as gone, which only
 * the report sees.
 */
@SpringBootTest
class LibraryInsertCommandTests {

	static final String DONE = "2026-10-01T00:00:00+09:00";

	@Autowired
	LibraryRegistry registry;

	@Autowired
	LibraryCatalog catalog;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@TempDir
	Path dir;

	Path sqlite, rulesDir, reportDir;

	RulesFolder folder = new RulesFolder();

	LibraryInsertCommand command;

	@BeforeEach
	void setUp() throws Exception {
		sqlite = dir.resolve("ebook-catalog-test.sqlite");
		rulesDir = dir.resolve("rules");
		reportDir = dir.resolve("reports");
		command = new LibraryInsertCommand(registry, jdbc, transactionManager, Clock.systemUTC());
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE library (id INTEGER PRIMARY KEY, name TEXT, platform_type TEXT, base_url TEXT,"
					+ " last_full_success_at TEXT, crawl_skip TEXT, vendor TEXT)");
			s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, removed_at TEXT)");
		}
	}

	@AfterEach
	void cleanUp() {
		jdbc.sql("DELETE FROM collection.library_insert_run").update();
		jdbc.sql("""
				DELETE FROM library.library WHERE id IN
				  (SELECT library_id FROM collection.collection_link WHERE collection_library_id >= 900000)""")
			.update();
		jdbc.sql("DELETE FROM collection.collection_link WHERE collection_library_id >= 900000").update();
	}

	@Test
	void previewWritesTheReportAndNothingElse() {
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test", "https://new-univ.test");
		int libraries = count("SELECT count(*) FROM library.library");

		LibraryInsertCommand.Outcome outcome = command.preview(sqlite, folder.write(rulesDir), reportDir);

		assertThat(outcome.exitCode()).isZero();
		assertThat(outcome.reportFile()).exists().hasFileName("library-insert-" + outcome.reportId() + ".json");
		assertThat(outcome.plan().newLibraries()).hasSize(1);
		assertThat(outcome.plan().firstId()).isEqualTo(registry.nextId());
		assertThat(count("SELECT count(*) FROM library.library")).isEqualTo(libraries);
		assertThat(count("SELECT count(*) FROM collection.library_insert_run")).isZero();
	}

	@Test
	void insertPutsTheLibrariesInAtOnce() { // AC-4, AC-12
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test", "https://new-univ.test");
		collect(900002, "새 도서관", "YES24_V2", "https://new-public.test", DONE);
		folder.kind(900002, "PUBLIC").origin("https://new-public.test")
			.publicJoin(900002, "REGION", "RESIDENCE:11;WORK:11", "서울 거주·재직");
		LibraryInsertCommand.Outcome preview = command.preview(sqlite, folder.write(rulesDir), reportDir);

		LibraryInsertCommand.Outcome insert = command.insert(sqlite, rulesDir, reportDir, preview.reportId());

		assertThat(insert.exitCode()).isZero();
		assertThat(insert.inserted()).isEqualTo(2);
		long first = preview.plan().firstId();
		assertThat(jdbc.sql("""
				SELECT l.name, l.base_url, l.homepage_url, l.join_survey, k.collection_library_id
				FROM library.library l JOIN collection.collection_link k ON k.library_id = l.id WHERE l.id = :id""")
			.param("id", first)
			.query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
					rs.getLong(5)))
			.single()).containsExactly("새 대학교", "https://new-univ.test", "https://new-univ.test", "MEMBERS_ONLY",
					900001L);
		assertThat(count("SELECT count(*) FROM library.eligibility_rule WHERE library_id = " + (first + 1)))
			.isEqualTo(2);
		Map<String, Object> run = jdbc.sql("""
				SELECT report_id, snapshot, inserted_count, join_surveyed_on::text AS surveyed, status,
				  ran_at IS NOT NULL AS has_time, jsonb_array_length(payload -> 'newLibraries') AS entries
				FROM collection.library_insert_run""")
			.query()
			.singleRow();
		assertThat(run).containsEntry("report_id", preview.reportId())
			.containsEntry("snapshot", "ebook-catalog-test.sqlite")
			.containsEntry("inserted_count", 2)
			.containsEntry("surveyed", "2026-10-05")
			.containsEntry("status", "SUCCEEDED")
			.containsEntry("has_time", true)
			.containsEntry("entries", 2);
		// no restart: the catalog reads the table on every call
		assertThat(catalog.enabledLibraryRefs()).extracting(LibraryRef::id).contains(first, first + 1);
	}

	@Test
	void aSecondPreviewHasNothingToDo() { // AC-8
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test");
		String id = command.preview(sqlite, folder.write(rulesDir), reportDir).reportId();
		command.insert(sqlite, rulesDir, reportDir, id);

		LibraryInsertCommand.Outcome again = command.preview(sqlite, rulesDir, reportDir);

		assertThat(again.plan().newLibraries()).isEmpty();
		assertThat(again.exitCode()).isZero();
		LibraryInsertCommand.Outcome nothing = command.insert(sqlite, rulesDir, reportDir, again.reportId());
		assertThat(nothing.inserted()).isZero();
		assertThat(count("SELECT count(*) FROM collection.library_insert_run")).isEqualTo(1);
	}

	@Test
	void aChangedRulesFileStopsTheInsert() throws Exception { // AC-5
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test");
		String id = command.preview(sqlite, folder.write(rulesDir), reportDir).reportId();
		Files.writeString(rulesDir.resolve("demo_or_closed.tsv"), "999999\t없는 곳\t2026-10-12\t\n",
				StandardOpenOption.APPEND);
		int libraries = count("SELECT count(*) FROM library.library");

		assertThatThrownBy(() -> command.insert(sqlite, rulesDir, reportDir, id))
			.isInstanceOf(LibraryInsertException.class)
			.hasMessageContaining("demo_or_closed.tsv")
			.satisfies(ex -> assertThat(((LibraryInsertException) ex).exitCode()).isEqualTo(4));
		assertThat(count("SELECT count(*) FROM library.library")).isEqualTo(libraries);
		assertThat(count("SELECT count(*) FROM collection.library_insert_run")).isZero();
	}

	@Test
	void aMissingReportStops() {
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test");
		folder.write(rulesDir);

		assertThatThrownBy(() -> command.insert(sqlite, rulesDir, reportDir, "0123456789ab"))
			.isInstanceOf(LibraryInsertException.class)
			.satisfies(ex -> assertThat(((LibraryInsertException) ex).exitCode()).isEqualTo(4));
		assertThatThrownBy(() -> command.insert(sqlite, rulesDir, reportDir, null))
			.isInstanceOf(LibraryInsertException.class);
	}

	@Test
	void aSuspectStopsTheInsert() throws Exception {
		collect(900001, "하나", "YES24_V2", "https://shared.test", DONE);
		collect(900002, "둘", "YES24_V2", "https://shared.test:8080", DONE);
		folder.kind(900001, "UNIV").kind(900002, "UNIV").origin("https://shared.test").origin("https://shared.test:8080");
		holding(900001, "A");
		holding(900002, "A");

		LibraryInsertCommand.Outcome preview = command.preview(sqlite, folder.write(rulesDir), reportDir);
		assertThat(preview.exitCode()).isEqualTo(2);

		LibraryInsertCommand.Outcome insert = command.insert(sqlite, rulesDir, reportDir, preview.reportId());
		assertThat(insert.exitCode()).isEqualTo(2);
		assertThat(insert.inserted()).isZero();
		assertThat(count("SELECT count(*) FROM collection.collection_link WHERE collection_library_id >= 900000"))
			.isZero();
	}

	@Test
	void aRefusedRowRollsEverythingBackAndKeepsAFailedRun() {
		collect(900001, "새 대학교", "YES24_V2", "http://new-univ.test", DONE);
		folder.kind(900001, "UNIV").origin("http://new-univ.test");
		// the address of pilot library 2: base_url is UNIQUE
		collect(900002, "같은 주소", "KYOBO_V1", "http://dup.test", DONE);
		folder.kind(900002, "UNIV").origin("http://dup.test", "https://www.ghlib.go.kr:446");
		String id = command.preview(sqlite, folder.write(rulesDir), reportDir).reportId();
		int libraries = count("SELECT count(*) FROM library.library");

		assertThatThrownBy(() -> command.insert(sqlite, rulesDir, reportDir, id))
			.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("SELECT count(*) FROM library.library")).isEqualTo(libraries);
		assertThat(count("SELECT count(*) FROM collection.collection_link WHERE collection_library_id >= 900000"))
			.isZero();
		Map<String, Object> run = jdbc.sql("""
				SELECT status, inserted_count, failure_detail IS NOT NULL AS detailed,
				  jsonb_array_length(payload -> 'newLibraries') AS entries FROM collection.library_insert_run""")
			.query()
			.singleRow();
		assertThat(run).containsEntry("status", "FAILED")
			.containsEntry("inserted_count", 0)
			.containsEntry("detailed", true)
			.containsEntry("entries", 2);
	}

	private void collect(long id, String name, String platform, String baseUrl, String fullSuccess) {
		sql("INSERT INTO library VALUES (" + id + ", '" + name + "', '" + platform + "', '" + baseUrl + "', "
				+ (fullSuccess == null ? "NULL" : "'" + fullSuccess + "'") + ", NULL, NULL)");
	}

	private void holding(long libraryId, String key) {
		sql("INSERT INTO holding VALUES (" + libraryId + ", '" + key + "', NULL)");
	}

	private void sql(String statement) {
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute(statement);
		}
		catch (java.sql.SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private int count(String sql) {
		return jdbc.sql(sql).query(Integer.class).single();
	}

}
