package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** Cycle 21 handoff step 3 (ADR-0125): {@code lendingKinds} against db-test; the seed is what Liquibase left. */
@SpringBootTest
@Transactional
class LibraryLendingKindsTests {

	@Autowired
	LibraryCatalog catalog;

	@Autowired
	JdbcClient jdbc;

	@Test
	void everyEnabledLibraryWithWhetherItIsSubscription() {
		Map<Long, Boolean> kinds = catalog.lendingKinds();

		long enabled = jdbc.sql("SELECT count(*) FROM library.library WHERE enabled").query(Long.class).single();
		long subscription = jdbc
			.sql("SELECT count(*) FROM library.library WHERE enabled AND lending_model = 'SUBSCRIPTION'")
			.query(Long.class)
			.single();
		assertThat(kinds).hasSize((int) enabled);
		assertThat(kinds.values().stream().filter(Boolean::booleanValue).count()).isEqualTo(subscription)
			.isPositive();
		// null (portals), OWNED, MIXED and RENTAL are not subscription
		long other = jdbc
			.sql("SELECT id FROM library.library WHERE enabled AND lending_model IS DISTINCT FROM 'SUBSCRIPTION' LIMIT 1")
			.query(Long.class)
			.single();
		assertThat(kinds).containsEntry(other, false);
	}

	@Test
	void disabledLibrariesAreLeftOut() {
		long on = jdbc.sql("SELECT id FROM library.library WHERE enabled AND lending_model = 'SUBSCRIPTION' LIMIT 1")
			.query(Long.class)
			.single();
		jdbc.sql("UPDATE library.library SET enabled = false WHERE id = :id").param("id", on).update();

		Map<Long, Boolean> kinds = catalog.lendingKinds();

		assertThat(kinds).doesNotContainKey(on);
		assertThat(kinds).doesNotContainKeys(59L, 131L); // off since the Kyobo seed
	}

}
