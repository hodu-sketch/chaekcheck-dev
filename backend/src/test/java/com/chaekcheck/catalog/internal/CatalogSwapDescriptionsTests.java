package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * The swap deletes the book descriptions of book ids the new catalog no longer has (cycle 26 step 4, ADR-0162, AC-6):
 * two books merged into one keep the kept id's rows and lose the other's, a book gone loses its rows, the count is
 * returned, and a swap that gives up on a held lock deletes nothing. The same short lock settings as
 * {@link CatalogLoadSwapTests}, so both share one context.
 */
@SpringBootTest(properties = { "chaekcheck.catalog.load.lock-timeout=200ms",
		"chaekcheck.catalog.load.swap-retry-wait=100ms", "chaekcheck.catalog.load.swap-attempts=2" })
class CatalogSwapDescriptionsTests {

	private static final String KEY = "0123456789abcdef0123456789abcdef01234567";

	@Autowired
	CatalogLoad catalogLoad;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	@BeforeEach
	@AfterEach
	void empty() {
		jdbc.sql("""
				TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot,
				  catalog.book_summary, catalog.book_cover, catalog.book_profile, catalog.book_search, catalog.book_tag""")
			.update();
		jdbc.sql("DELETE FROM catalog.tag_dictionary WHERE code = 'swap1'").update();
	}

	@Test
	void mergedBooksKeepTheKeptIdsDescriptionsOnly() {
		load(Map.of("A", List.of("1:a1"), "B", List.of("1:b1")));
		long a = idOf("A");
		long b = idOf("B");
		describe(a);
		describe(b);

		// one book now holds both copies: B's id is merged away
		CatalogLoad.Result result = load(Map.of("A", List.of("1:a1", "1:b1")));

		assertThat(idOf("A")).isEqualTo(a);
		assertThat(rows(a)).isEqualTo(5);
		assertThat(rows(b)).isZero();
		assertThat(result.descriptionsDeleted()).isEqualTo(5);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.tag_dictionary WHERE code = 'swap1'")
			.query(Long.class)
			.single()).isOne();
	}

	@Test
	void aBookGoneLosesItsDescriptionsAndNothingElseIsTouched() {
		load(Map.of("A", List.of("1:a1"), "B", List.of("1:b1")));
		long a = idOf("A");
		long b = idOf("B");
		describe(a);
		describe(b);

		assertThat(load(Map.of("A", List.of("1:a1"))).descriptionsDeleted()).isEqualTo(5);
		assertThat(rows(b)).isZero();
		assertThat(load(Map.of("A", List.of("1:a1"))).descriptionsDeleted()).isZero();
		assertThat(rows(a)).isEqualTo(5);
	}

	@Test
	void aSwapThatGivesUpDeletesNothing() throws Exception {
		load(Map.of("A", List.of("1:a1"), "B", List.of("1:b1")));
		long b = idOf("B");
		describe(b);
		try (Connection reader = dataSource.getConnection()) {
			reader.setAutoCommit(false);
			try (Statement statement = reader.createStatement()) {
				statement.execute("LOCK TABLE catalog.book IN ACCESS SHARE MODE");
			}
			assertThatThrownBy(() -> load(Map.of("A", List.of("1:a1"))))
				.isInstanceOf(CatalogLoad.NotSwappedException.class);
			reader.rollback();
		}
		assertThat(rows(b)).isEqualTo(5);
	}

	/** One row in each of the five description tables. */
	private void describe(long bookId) {
		jdbc.sql("INSERT INTO catalog.tag_dictionary (code, axis, name) VALUES ('swap1', 'mood', '잔잔한')"
				+ " ON CONFLICT DO NOTHING").update();
		jdbc.sql("INSERT INTO catalog.book_summary VALUES (?, '요약이에요.', DATE '2026-10-28')").param(bookId).update();
		jdbc.sql("INSERT INTO catalog.book_cover VALUES (?, ?)").params(bookId, KEY).update();
		jdbc.sql("INSERT INTO catalog.book_profile (book_id) VALUES (?)").param(bookId).update();
		jdbc.sql("INSERT INTO catalog.book_search (book_id, search_text) VALUES (?, '책')").param(bookId).update();
		jdbc.sql("INSERT INTO catalog.book_tag VALUES (?, 'swap1', DATE '2026-10-28')").param(bookId).update();
	}

	private long rows(long bookId) {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM catalog.book_summary WHERE book_id = :id)
				  + (SELECT count(*) FROM catalog.book_cover WHERE book_id = :id)
				  + (SELECT count(*) FROM catalog.book_profile WHERE book_id = :id)
				  + (SELECT count(*) FROM catalog.book_search WHERE book_id = :id)
				  + (SELECT count(*) FROM catalog.book_tag WHERE book_id = :id)""")
			.param("id", bookId)
			.query(Long.class)
			.single();
	}

	private long idOf(String title) {
		return jdbc.sql("SELECT id FROM catalog.book WHERE title = ?").param(title).query(Long.class).single();
	}

	/** @param holdings book title → "libraryId:itemKey" per holding */
	private CatalogLoad.Result load(Map<String, List<String>> holdings) {
		List<String> order = holdings.keySet().stream().sorted().toList();
		return catalogLoad.replaceAll("test-swap", session -> {
			Map<String, Long> loadIds = new HashMap<>();
			for (String title : order) {
				loadIds.put(title, session.addBook(new NewBook(title, "가", "나", title, title, "나", null, null, "EBOOK",
						new long[] { 1 }, List.of(new Contributor("가", null, "가", "가", Role.AUTHOR, 0)))));
			}
			for (String title : order) {
				for (String h : holdings.get(title)) {
					long library = Long.parseLong(h.substring(0, h.indexOf(':')));
					String key = h.substring(h.indexOf(':') + 1);
					session.addHolding(new NewHolding(loadIds.get(title), library, "KYOBO_V1", key, "https://lib.test/" + key,
							"EBOOK", title, "가", "나", null, null, null, Instant.parse("2026-10-01T00:00:00Z")));
				}
			}
			session.addLibrarySnapshot(1, Instant.parse("2026-10-01T00:00:00Z"));
		});
	}

}
