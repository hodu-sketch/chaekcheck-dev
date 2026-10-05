package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

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
 * Book ids carry over between loads (plan catalog-refresh.md 5.2, user request 2026-10-05): same holdings keep the
 * id, a merge keeps the smallest id, a split leaves the id with the part sharing most holdings, and new ids are never
 * ids given before. The load commits on its own connection, so the tables are emptied around each test.
 */
@SpringBootTest
class CatalogLoadBookIdTests {

	@Autowired
	CatalogLoad catalogLoad;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	@AfterEach
	void empty() {
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
	}

	@Test
	void idsCarryOverThroughMergesAndSplits() {
		// load 1: A, B, C, D (D gets the highest id)
		load(Map.of("A", List.of("1:a1", "1:a2"), "B", List.of("1:b1"), "C", List.of("2:c1", "2:c2", "2:c3"), "D",
				List.of("1:d1")), List.of("A", "B", "C", "D"));
		Map<String, Long> first = ids();
		assertThat(first.get("A")).isLessThan(first.get("B"));
		assertThat(first.get("C")).isLessThan(first.get("D"));

		// load 2: A unchanged, B and D merged, C split (C1 keeps two of its three holdings), E is new
		CatalogLoad.Result result = load(Map.of("E", List.of("3:e1"), "C2", List.of("2:c3"), "A",
				List.of("1:a2", "1:a1"), "BD", List.of("1:d1", "1:b1"), "C1", List.of("2:c1", "2:c2")),
				List.of("E", "C2", "A", "BD", "C1"));
		Map<String, Long> second = ids();

		assertThat(second.get("A")).isEqualTo(first.get("A"));
		assertThat(second.get("BD")).isEqualTo(first.get("B"));
		assertThat(second.get("C1")).isEqualTo(first.get("C"));
		// new ids start above every id given before, D's included although no book has it now
		assertThat(second.get("E")).isGreaterThan(first.get("D"));
		assertThat(second.get("C2")).isGreaterThan(first.get("D"));
		assertThat(second.values()).doesNotContain(first.get("D"));
		assertThat(result.keptBookIds()).isEqualTo(3);
		assertThat(result.newBookIds()).isEqualTo(2);
		assertThat(result.droppedBookIds()).isEqualTo(1);

		// holdings and contributors point at the stored ids
		assertThat(jdbc.sql("""
				SELECT count(*) FROM catalog.holding h JOIN catalog.book b ON b.id = h.book_id
				WHERE b.title = 'BD' AND h.item_key IN ('b1', 'd1')""").query(Long.class).single()).isEqualTo(2);
		assertThat(jdbc.sql("""
				SELECT b.title FROM catalog.book_contributor c JOIN catalog.book b ON b.id = c.book_id
				ORDER BY b.title""").query(String.class).list()).containsExactly("A", "BD", "C1", "C2", "E");

		// load 3 with nothing shared: every book is new, and the ids still go up
		CatalogLoad.Result third = load(Map.of("F", List.of("4:f1")), List.of("F"));
		assertThat(ids().get("F")).isGreaterThan(second.get("E")).isGreaterThan(second.get("C2"));
		assertThat(third.keptBookIds()).isZero();
		assertThat(third.droppedBookIds()).isEqualTo(5);
	}

	/** @param holdings book title → "libraryId:itemKey" per holding; @param order the order books are added in */
	private CatalogLoad.Result load(Map<String, List<String>> holdings, List<String> order) {
		return catalogLoad.replaceAll("test-book-ids", session -> {
			Map<String, Long> loadIds = new java.util.HashMap<>();
			for (String title : order) {
				long[] libraries = holdings.get(title)
					.stream()
					.mapToLong(h -> Long.parseLong(h.substring(0, h.indexOf(':'))))
					.distinct()
					.sorted()
					.toArray();
				loadIds.put(title, session.addBook(new NewBook(title, "가", "나", title, title, "나", null, null, "EBOOK",
						libraries, List.of(new Contributor("가", null, "가", "가", Role.AUTHOR, 0)))));
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
		});
	}

	private Map<String, Long> ids() {
		return jdbc.sql("SELECT title, id FROM catalog.book")
			.query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
			.list()
			.stream()
			.collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
	}

}
