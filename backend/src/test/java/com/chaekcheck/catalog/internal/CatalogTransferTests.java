package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.TestPgCommand;
import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.Discarded;
import com.chaekcheck.catalog.CatalogLoad.Manifest;
import com.chaekcheck.catalog.CatalogLoad.NewBook;
import com.chaekcheck.catalog.CatalogLoad.NewHolding;
import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/**
 * Export, apply, finish and discard (cycle 11 handoff step 4, ADR-0078) with the real PostgreSQL 18 tools inside the
 * db-test container. One database plays both sides in turn: export on "the PC", discard, then apply the same file as
 * "production". The tools run through {@link TestPgCommand}.
 */
@SpringBootTest
class CatalogTransferTests {

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	@TempDir
	Path dir;

	JdbcCatalogLoad catalogLoad;

	@BeforeEach
	void setUp() {
		catalogLoad = new JdbcCatalogLoad(dataSource, new CatalogLoadSettings(Duration.ofMillis(200), 2,
				Duration.ofMillis(100), null, null, null, List.of()), new PgTools(TestPgCommand.get()));
		empty();
	}

	@AfterEach
	void empty() {
		jdbc.sql("DROP SCHEMA IF EXISTS catalog_next CASCADE").update();
		jdbc.sql("ALTER TABLE catalog.book DROP COLUMN IF EXISTS transfer_test_column").update();
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
	}

	@Test
	void exportKeepsTheCatalogAndLeavesWaitingTables() { // AC-1
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		CatalogLoad.Baseline before = catalogLoad.baseline();
		load(catalogLoad::prepare, Map.of("A", List.of("1:a1"), "B", List.of("1:b1", "2:b2")));
		Path file = dir.resolve("export.dump");

		Manifest manifest = catalogLoad.export(file, "{\"source\": \"test \\\\ \\\"x\\\"\"}");

		assertThat(titles("catalog")).containsExactly("A");
		assertThat(file).isRegularFile();
		assertThat(dir.resolve("export.dump.part")).doesNotExist();
		assertThat(catalogLoad.hasWaitingExport()).isTrue();
		assertThat(manifest.baseline()).isEqualTo(before);
		Manifest read = catalogLoad.readManifest(file);
		assertThat(read).isEqualTo(manifest);
		assertThat(read.notes()).isEqualTo("{\"source\":\"test \\\\ \\\"x\\\"\"}");
	}

	@Test
	void appliedFileGivesTheExportedIds() { // AC-2
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1"), "B", List.of("1:b1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1"), "C", List.of("2:c1", "2:c2")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		List<String> books = rows("catalog_next.book", "id || ' ' || title");
		List<String> holdings = rows("catalog_next.holding", "id || ' ' || book_id || ' ' || library_id || ':' || item_key");
		List<Long> sequences = sequences("catalog_next");

		assertThat(catalogLoad.discardPrepared()).isEqualTo(Discarded.WAITING);
		CatalogLoad.Result result = catalogLoad.apply(file);

		assertThat(rows("catalog.book", "id || ' ' || title")).isEqualTo(books);
		assertThat(rows("catalog.holding", "id || ' ' || book_id || ' ' || library_id || ':' || item_key"))
			.isEqualTo(holdings);
		assertThat(sequences("catalog")).isEqualTo(sequences);
		assertThat(result.books()).isEqualTo(2);
		assertThat(result.holdings()).isEqualTo(3);
		assertThat(result.steps()).containsOnlyKeys("restore", "index", "analyze", "swap");
		assertNoNextSchema();
		// the indexes and constraints are the catalog's own again
		assertThat(jdbc.sql("""
				SELECT count(*) FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace
				WHERE n.nspname = 'catalog' AND c.conname = 'holding_book_id_fkey'""").query(Long.class).single())
			.isEqualTo(1);
	}

	@Test
	void otherCatalogIsAMismatch() { // AC-4 (book table)
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		catalogLoad.discardPrepared();
		jdbc.sql("DELETE FROM catalog.holding").update();

		assertThatThrownBy(() -> catalogLoad.apply(file)).isInstanceOfSatisfying(CatalogLoad.NotSwappedException.class,
				ex -> assertThat(ex.reason()).isEqualTo("mismatch"));
		assertThat(titles("catalog")).containsExactly("A");
		assertNoNextSchema();
	}

	@Test
	void otherColumnsAreAMismatch() { // AC-11
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		catalogLoad.discardPrepared();
		jdbc.sql("ALTER TABLE catalog.book ADD COLUMN transfer_test_column integer").update();

		assertThatThrownBy(() -> catalogLoad.apply(file)).isInstanceOfSatisfying(CatalogLoad.NotSwappedException.class,
				ex -> assertThat(ex.reason()).isEqualTo("mismatch"));
		assertThat(titles("catalog")).containsExactly("A");
	}

	@Test
	void waitingTablesStopAnotherLoad() { // AC-12
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		catalogLoad.export(dir.resolve("export.dump"), "{}");

		assertThatThrownBy(() -> load(catalogLoad::prepare, Map.of("C", List.of("1:c1"))))
			.hasMessageContaining("first finish them");
		assertThatThrownBy(() -> load(catalogLoad::replaceAll, Map.of("C", List.of("1:c1"))))
			.hasMessageContaining("first finish them");
		assertThat(titles("catalog_next")).containsExactly("B");
		assertThat(catalogLoad.hasWaitingExport()).isTrue();
		assertThat(titles("catalog")).containsExactly("A");
	}

	@Test
	void finishAfterAStoppedFinishRunsAgain() { // AC-15 (in part)
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1"), "C", List.of("1:c1")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		// a finish that stopped while making indexes: one index made, then a duplicate key the next one fails on
		jdbc.sql("CREATE INDEX holding_library_id_idx_left ON catalog_next.holding (library_id)").update();
		jdbc.sql("ALTER TABLE catalog_next.book ADD CONSTRAINT book_pkey PRIMARY KEY (id)").update();
		jdbc.sql("INSERT INTO catalog_next.holding SELECT * FROM catalog_next.holding LIMIT 1").update();

		assertThatThrownBy(() -> catalogLoad.finishPrepared(file)).isInstanceOf(RuntimeException.class);
		assertThat(catalogLoad.hasWaitingExport()).isTrue();
		assertThat(titles("catalog")).containsExactly("A");

		jdbc.sql("""
				DELETE FROM catalog_next.holding a USING catalog_next.holding b
				WHERE a.id = b.id AND a.ctid > b.ctid""").update();
		CatalogLoad.Result result = catalogLoad.finishPrepared(file);

		assertThat(titles("catalog")).containsExactly("B", "C");
		assertThat(result.steps()).containsOnlyKeys("index", "analyze", "swap");
		assertNoNextSchema();
		assertThat(jdbc.sql("SELECT count(*) FROM pg_class WHERE relname = 'holding_library_id_idx_left'")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void finishNeedsTheFilesWaitingTables() {
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		catalogLoad.discardPrepared();

		assertThatThrownBy(() -> catalogLoad.finishPrepared(file)).hasMessageContaining("apply");
		assertThat(titles("catalog")).containsExactly("A");
	}

	@Test
	void discardDropsWhateverIsThere() { // AC-13 (the three cases)
		assertThat(catalogLoad.discardPrepared()).isEqualTo(Discarded.NOTHING);

		jdbc.sql("CREATE SCHEMA catalog_next").update();
		jdbc.sql("CREATE TABLE catalog_next.book (id bigint)").update();
		assertThat(catalogLoad.discardPrepared()).isEqualTo(Discarded.LEFT_OVER);
		assertNoNextSchema();

		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		catalogLoad.export(dir.resolve("export.dump"), "{}");
		assertThat(catalogLoad.discardPrepared()).isEqualTo(Discarded.WAITING);
		assertNoNextSchema();
	}

	@Test
	void brokenFileKeepsTheCatalog() throws Exception { // AC-5 (restore fails)
		load(catalogLoad::replaceAll, Map.of("A", List.of("1:a1")));
		load(catalogLoad::prepare, Map.of("B", List.of("1:b1")));
		Path file = dir.resolve("export.dump");
		catalogLoad.export(file, "{}");
		catalogLoad.discardPrepared();
		byte[] bytes = Files.readAllBytes(file);
		Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 40));

		assertThatThrownBy(() -> catalogLoad.apply(file)).isInstanceOf(RuntimeException.class);
		assertThat(titles("catalog")).containsExactly("A");
		assertNoNextSchema();
	}

	@Test
	void exportNeedsPreparedTablesAndANewFile() throws Exception {
		assertThatThrownBy(() -> catalogLoad.export(dir.resolve("none.dump"), "{}"))
			.hasMessageContaining("No new tables");
		Path existing = Files.writeString(dir.resolve("existing.dump"), "x");
		assertThatThrownBy(() -> catalogLoad.export(existing, "{}")).hasMessageContaining("exists already");
	}

	private void assertNoNextSchema() {
		assertThat(jdbc.sql("SELECT count(*) FROM pg_namespace WHERE nspname = 'catalog_next'")
			.query(Long.class)
			.single()).isZero();
	}

	private List<String> titles(String schema) {
		return jdbc.sql("SELECT title FROM " + schema + ".book ORDER BY title").query(String.class).list();
	}

	private List<String> rows(String table, String expression) {
		return jdbc.sql("SELECT " + expression + " FROM " + table + " ORDER BY id").query(String.class).list();
	}

	/** {@code last_value} and {@code is_called} of the book and holding identities. */
	private List<Long> sequences(String schema) {
		List<Long> values = new ArrayList<>();
		for (String table : List.of("book", "holding")) {
			String sequence = jdbc.sql("SELECT pg_get_serial_sequence('" + schema + "." + table + "', 'id')")
				.query(String.class)
				.single();
			values.add(jdbc.sql("SELECT last_value * 2 + CASE WHEN is_called THEN 1 ELSE 0 END FROM " + sequence)
				.query(Long.class)
				.single());
		}
		return values;
	}

	@FunctionalInterface
	private interface LoadCall {

		CatalogLoad.Result call(String source, CatalogLoad.Loader loader);

	}

	/** @param holdings book title → "libraryId:itemKey" per holding */
	private static CatalogLoad.Result load(LoadCall call, Map<String, List<String>> holdings) {
		List<String> order = holdings.keySet().stream().sorted().toList();
		return call.call("test-transfer", session -> {
			Map<String, Long> loadIds = new HashMap<>();
			for (String title : order) {
				loadIds.put(title, session.addBook(new NewBook(title, "가", "나", title, title, "나", null, null, "EBOOK",
						new long[] { 1 }, List.of(new Contributor("가", null, "가", "가", Role.AUTHOR, 0)))));
			}
			for (String title : order) {
				for (String h : holdings.get(title)) {
					long library = Long.parseLong(h.substring(0, h.indexOf(':')));
					String key = h.substring(h.indexOf(':') + 1);
					session.addHolding(new NewHolding(loadIds.get(title), library, "KYOBO_V1", key,
							"https://lib.test/" + key, "EBOOK", title, "가", "나", null, null, null,
							Instant.parse("2026-10-01T00:00:00Z")));
				}
			}
			session.addLibrarySnapshot(1, Instant.parse("2026-10-01T00:00:00Z"));
		});
	}

}
