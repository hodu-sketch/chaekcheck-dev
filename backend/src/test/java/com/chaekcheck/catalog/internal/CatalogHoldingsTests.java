package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.CatalogHoldings;
import com.chaekcheck.catalog.HoldingRef;

/** {@code holdingsOf} reads the plan libraries' rows only (plan ADR-0066, cycle 08 handoff step 5-2). */
@SpringBootTest
@Transactional
class CatalogHoldingsTests {

	@Autowired
	CatalogHoldings catalogHoldings;

	@Autowired
	JdbcClient jdbc;

	@Test
	void holdingsOfThePlanLibrariesOnlyInBookOrder() {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, author_display, publisher, title_norm, main_title_norm,
				  publisher_norm, content_type, library_ids)
				VALUES (900001, '가', '가', '가', '가', '가', '가', 'EBOOK', '{1,2,3}'),
				  (900002, '나', '나', '나', '나', '나', '나', 'EBOOK', '{3}')""").update();
		jdbc.sql("""
				INSERT INTO catalog.holding (id, book_id, library_id, platform, item_key, detail_url, content_type,
				  title_raw, first_seen_at)
				VALUES (800001, 900001, 1, 'KYOBO_V1', 'a1', 'https://l.test/a1', 'EBOOK', '가', now()),
				  (800002, 900001, 2, 'KYOBO_V1', 'a2', 'https://l.test/a2', 'EBOOK', '가', now()),
				  (800003, 900001, 3, 'KYOBO_V1', 'a3', 'https://l.test/a3', 'EBOOK', '가', now()),
				  (800004, 900002, 3, 'KYOBO_V1', 'b3', 'https://l.test/b3', 'EBOOK', '나', now())""").update();

		assertThat(catalogHoldings.holdingsOf(List.of(900002L, 900001L), List.of(3L, 1L)))
			.extracting(HoldingRef::holdingId)
			.containsExactly(800004L, 800001L, 800003L);
		assertThat(catalogHoldings.holdingsOf(List.of(900001L), List.of())).isEmpty();
	}

}
