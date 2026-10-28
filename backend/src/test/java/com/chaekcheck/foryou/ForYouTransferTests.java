package com.chaekcheck.foryou;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.Iterator;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.foryou.ForYouTransfer.Neighbours;

/**
 * Neighbours full swap and add (cycle 23 step 7; blurbs and covers are written to catalog since cycle 26). Not transactional: the full load uses its own
 * connection and DDL, so the tables are emptied after each test.
 */
@SpringBootTest
class ForYouTransferTests {

	static final LocalDate DAY = LocalDate.of(2026, 10, 24);

	@Autowired
	ForYouTransfer transfer;

	@Autowired
	JdbcClient jdbc;

	@AfterEach
	void empty() {
		jdbc.sql("TRUNCATE foryou.book_neighbour, foryou.book_blurb, foryou.book_cover").update();
	}

	@Test
	void fullSwapReplacesTheWholeTableAndKeepsItsRules() {
		transfer.upsertNeighbours(List.of(new Neighbours(1, new int[] { 2 })).iterator(), DAY);

		long written = transfer.replaceAllNeighbours(
				List.of(new Neighbours(5, new int[] { 6, 7 }), new Neighbours(6, new int[] { 5 })).iterator(),
				DAY.plusDays(1));

		assertThat(written).isEqualTo(2);
		assertThat(rows()).containsExactly("5:{6,7}:2026-10-25", "6:{5}:2026-10-25");
		assertThat(jdbc.sql("SELECT count(*) FROM pg_indexes WHERE schemaname = 'foryou' AND indexname = 'book_neighbour_pkey'")
			.query(Long.class)
			.single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT to_regclass('foryou.book_neighbour_next') IS NULL").query(Boolean.class).single())
			.isTrue();
		assertThatThrownBy(() -> jdbc.sql("INSERT INTO foryou.book_neighbour VALUES (9, ARRAY[]::integer[], DATE '2026-10-24')")
			.update()).hasMessageContaining("book_neighbour_neighbour_ids_check");
	}

	@Test
	void aFailureBeforeTheSwapKeepsTheOldTable() {
		transfer.upsertNeighbours(List.of(new Neighbours(1, new int[] { 2 })).iterator(), DAY);
		Iterator<Neighbours> broken = new Iterator<>() {

			int left = 3;

			@Override
			public boolean hasNext() {
				return true;
			}

			@Override
			public Neighbours next() {
				if (left-- == 0) {
					throw new IllegalArgumentException("line 4: book 9 is its own neighbour");
				}
				return new Neighbours(10 + left, new int[] { 1 });
			}

		};

		assertThatThrownBy(() -> transfer.replaceAllNeighbours(broken, DAY)).hasMessageContaining("own neighbour");
		assertThat(rows()).containsExactly("1:{2}:2026-10-24");
		assertThat(jdbc.sql("SELECT to_regclass('foryou.book_neighbour_next') IS NULL").query(Boolean.class).single())
			.isTrue();
	}

	@Test
	void addUpserts() {
		transfer.upsertNeighbours(List.of(new Neighbours(1, new int[] { 2 }), new Neighbours(2, new int[] { 1 })).iterator(),
				DAY);
		transfer.upsertNeighbours(List.of(new Neighbours(2, new int[] { 3, 1 }), new Neighbours(3, new int[] { 2 })).iterator(),
				DAY.plusDays(1));

		assertThat(rows()).containsExactly("1:{2}:2026-10-24", "2:{3,1}:2026-10-25", "3:{2}:2026-10-25");
	}

	@Test
	void neighbourRules() {
		assertThatThrownBy(() -> new Neighbours(1, new int[] { 1 })).hasMessageContaining("own neighbour");
		assertThatThrownBy(() -> new Neighbours(1, new int[] { 2, 2 })).hasMessageContaining("twice");
		assertThatThrownBy(() -> new Neighbours(1, new int[0])).hasMessageContaining("0 neighbours");
		assertThatThrownBy(() -> new Neighbours(1, new int[21])).hasMessageContaining("21 neighbours");
		assertThatThrownBy(() -> new Neighbours(2_147_483_648L, new int[] { 1 })).hasMessageContaining("out of range");
	}

	private List<String> rows() {
		return jdbc.sql("SELECT book_id || ':' || neighbour_ids::text || ':' || computed_on FROM foryou.book_neighbour"
				+ " ORDER BY book_id").query(String.class).list();
	}

}
