package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.NewBook;
import com.chaekcheck.catalog.CatalogLoad.NewHolding;
import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/**
 * The load into {@code catalog_next} and the swap (plan ADR-0065, ADR-0070, cycle 08 handoff step 4): holding ids
 * carry over, the swapped tables have the same definitions as the Liquibase ones, a held lock or a failure keeps the
 * previous tables and leaves no {@code catalog_next}, a foreign key from outside stops the swap, and the search reads
 * the previous rows while a load runs. Short lock settings keep the lock test quick.
 */
@SpringBootTest(properties = { "chaekcheck.catalog.load.lock-timeout=200ms",
		"chaekcheck.catalog.load.swap-retry-wait=100ms", "chaekcheck.catalog.load.swap-attempts=2" })
class CatalogLoadSwapTests {

	@Autowired
	CatalogLoad catalogLoad;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@BeforeEach
	@AfterEach
	void empty() {
		jdbc.sql("DROP TABLE IF EXISTS public.swap_test_outside").update();
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
	}

	@Test
	void holdingIdsCarryOverAndNewOnesStartAboveEveryIdGiven() {
		load(Map.of("A", List.of("1:a1", "1:a2"), "B", List.of("1:b1")));
		Map<String, Long> first = holdingIds();
		long firstHighest = first.values().stream().mapToLong(Long::longValue).max().orElseThrow();

		// b1 left, c1 is new
		CatalogLoad.Result second = load(Map.of("A", List.of("1:a2", "1:a1"), "C", List.of("2:c1")));
		Map<String, Long> ids = holdingIds();
		assertThat(ids.get("1:a1")).isEqualTo(first.get("1:a1"));
		assertThat(ids.get("1:a2")).isEqualTo(first.get("1:a2"));
		assertThat(ids.get("2:c1")).isEqualTo(firstHighest + 1);
		assertThat(second.steps()).containsKeys("match", "write", "index", "analyze", "swap");

		// b1 comes back: it gets a new id, not its old one, and not c1's
		load(Map.of("A", List.of("1:a1", "1:a2"), "B", List.of("1:b1"), "C", List.of("2:c1")));
		Map<String, Long> third = holdingIds();
		assertThat(third.get("1:b1")).isEqualTo(firstHighest + 2).isNotEqualTo(first.get("1:b1"));
		assertThat(third.get("2:c1")).isEqualTo(ids.get("2:c1"));
		// R8: the rows were written in book order
		assertThat(jdbc.sql("SELECT count(*) FROM pg_namespace WHERE nspname = 'catalog_next'")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void swappedTablesHaveTheLiquibaseDefinitions() {
		List<String> before = definitions();
		load(Map.of("A", List.of("1:a1")));
		load(Map.of("A", List.of("1:a1"), "B", List.of("1:b1")));
		assertThat(definitions()).isEqualTo(before);
		// the identity keeps giving ids after the swap
		long id = jdbc.sql("""
				INSERT INTO catalog.book (title, author_display, publisher, title_norm, main_title_norm,
				  publisher_norm, content_type, library_ids)
				VALUES ('x', 'x', 'x', 'x', 'x', 'x', 'EBOOK', '{}') RETURNING id""").query(Long.class).single();
		assertThat(id).isGreaterThan(jdbc.sql("SELECT max(id) FROM catalog.book WHERE title <> 'x'")
			.query(Long.class)
			.single());
	}

	@Test
	void heldLockKeepsThePreviousTables() throws Exception {
		load(Map.of("A", List.of("1:a1")));
		try (Connection reader = dataSource.getConnection()) {
			reader.setAutoCommit(false);
			try (Statement statement = reader.createStatement()) {
				// a long search: its lock stays until the transaction ends
				statement.execute("LOCK TABLE catalog.book IN ACCESS SHARE MODE");
			}
			assertThatThrownBy(() -> load(Map.of("B", List.of("1:b1"))))
				.isInstanceOfSatisfying(CatalogLoad.NotSwappedException.class,
						ex -> assertThat(ex.reason()).isEqualTo("lock_not_acquired"));
			reader.rollback();
		}
		assertThat(titles()).containsExactly("A");
		assertNoNextSchema();
	}

	@Test
	void failureWhileWritingKeepsThePreviousTables() {
		load(Map.of("A", List.of("1:a1")));
		assertThatThrownBy(() -> catalogLoad.replaceAll("test-swap", session -> {
			session.addBook(book("B", 1));
			throw new IllegalStateException("stopped while reading");
		})).hasMessageContaining("stopped while reading");
		assertThat(titles()).containsExactly("A");
		assertNoNextSchema();
		// the next load still works
		load(Map.of("C", List.of("1:c1")));
		assertThat(titles()).containsExactly("C");
	}

	@Test
	void foreignKeyFromOutsideStopsTheSwap() {
		load(Map.of("A", List.of("1:a1")));
		jdbc.sql("CREATE TABLE public.swap_test_outside (book_id bigint REFERENCES catalog.book (id))").update();
		assertThatThrownBy(() -> load(Map.of("B", List.of("1:b1"))))
			.isInstanceOfSatisfying(CatalogLoad.NotSwappedException.class,
					ex -> assertThat(ex.reason()).isEqualTo("outside_foreign_key"));
		assertThat(titles()).containsExactly("A");
		assertNoNextSchema();
	}

	@Test
	void searchReadsThePreviousRowsWhileALoadRuns() {
		load(Map.of("A", List.of("1:a1")));
		List<List<String>> seen = new java.util.ArrayList<>();
		catalogLoad.replaceAll("test-swap", session -> {
			long b = session.addBook(book("B", 1));
			session.addHolding(holding(b, 1, "b1", "B"));
			seen.add(titles());
		});
		assertThat(seen).containsExactly(List.of("A"));
		assertThat(titles()).containsExactly("B");
	}

	private void assertNoNextSchema() {
		assertThat(jdbc.sql("SELECT count(*) FROM pg_namespace WHERE nspname = 'catalog_next'")
			.query(Long.class)
			.single()).isZero();
	}

	/** Columns, indexes and constraints of the four catalog tables, as text. */
	private List<String> definitions() {
		List<String> all = new java.util.ArrayList<>(jdbc.sql("""
				SELECT table_name || '.' || column_name || ' ' || data_type || ' ' || is_nullable || ' '
				  || coalesce(column_default, '') || ' ' || is_identity || ' ' || coalesce(identity_generation, '')
				FROM information_schema.columns WHERE table_schema = 'catalog' ORDER BY table_name, ordinal_position""")
			.query(String.class)
			.list());
		all.addAll(jdbc.sql("""
				SELECT pg_get_indexdef(i.indexrelid) FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'catalog' ORDER BY 1""")
			.query(String.class)
			.list());
		all.addAll(jdbc.sql("""
				SELECT t.relname || ' ' || c.conname || ' ' || c.contype::text || ' ' || pg_get_constraintdef(c.oid)
				FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace WHERE n.nspname = 'catalog' ORDER BY 1""")
			.query(String.class)
			.list());
		return all;
	}

	private List<String> titles() {
		return jdbc.sql("SELECT title FROM catalog.book ORDER BY title").query(String.class).list();
	}

	private Map<String, Long> holdingIds() {
		return jdbc.sql("SELECT library_id || ':' || item_key, id FROM catalog.holding")
			.query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

	/** @param holdings book title → "libraryId:itemKey" per holding */
	private CatalogLoad.Result load(Map<String, List<String>> holdings) {
		List<String> order = holdings.keySet().stream().sorted().toList();
		return catalogLoad.replaceAll("test-swap", session -> {
			Map<String, Long> loadIds = new HashMap<>();
			for (String title : order) {
				loadIds.put(title, session.addBook(book(title, 1)));
			}
			for (String title : order) {
				for (String h : holdings.get(title)) {
					long library = Long.parseLong(h.substring(0, h.indexOf(':')));
					session.addHolding(holding(loadIds.get(title), library, h.substring(h.indexOf(':') + 1), title));
				}
			}
			session.addLibrarySnapshot(1, Instant.parse("2026-10-01T00:00:00Z"));
		});
	}

	private static NewBook book(String title, long library) {
		return new NewBook(title, "가", "나", title, title, "나", null, null, "EBOOK", new long[] { library },
				List.of(new Contributor("가", null, "가", "가", Role.AUTHOR, 0)));
	}

	private static NewHolding holding(long bookId, long library, String key, String title) {
		return new NewHolding(bookId, library, "KYOBO_V1", key, "https://lib.test/" + key, "EBOOK", title, "가", "나",
				null, null, null, Instant.parse("2026-10-01T00:00:00Z"));
	}

}
