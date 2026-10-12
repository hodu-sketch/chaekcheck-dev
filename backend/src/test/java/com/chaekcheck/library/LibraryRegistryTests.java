package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle 13 (ADR-0083, ADR-0093): {@link LibraryRegistry} on db-test. Each test runs in a transaction that is rolled
 * back; the identity sequence is not rolled back, so ids are read from {@link LibraryRegistry#nextId()} each time.
 */
@SpringBootTest
class LibraryRegistryTests {

	@Autowired
	LibraryRegistry registry;

	@Autowired
	LibraryCatalog catalog;

	@Autowired
	JdbcClient jdbc;

	@Test
	@Transactional
	void addInsertsTheLibraryAndItsRules() {
		long id = registry.nextId();
		registry.add(List.of(library(id, "새 대학교", "https://new-univ.test",
				List.of(new NewEligibilityRule("RESIDENCE", "REGION", 11L), new NewEligibilityRule("WORK", "NATIONWIDE",
						null)))));

		assertThat(registry.listAll()).contains(new RegisteredLibrary(id, "새 대학교", "https://new-univ.test",
				PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED, 1, true));
		assertThat(jdbc.sql("""
				SELECT homepage_url, join_survey, enabled, library_type FROM library.library WHERE id = :id""")
			.param("id", id)
			.query((rs, n) -> List.of(rs.getString(1), rs.getString(2), rs.getBoolean(3), rs.getString(4)))
			.single()).containsExactly("https://new-univ.test", "MEMBERS_ONLY", true, "EBOOK");
		assertThat(jdbc.sql("SELECT count(*) FROM library.eligibility_rule WHERE library_id = :id")
			.param("id", id)
			.query(Long.class)
			.single()).isEqualTo(2);
	}

	@Test
	@Transactional
	void theSequenceMovesPastTheAddedIds() {
		long id = registry.nextId() + 5; // a gap, as the caller may choose
		registry.add(List.of(library(id, "번호 고른 곳", "https://gap.test", List.of())));

		long next = jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES ('IDENTITY 기본값', 'KYOBO_V1', 'KYOBO', 'OWNED', 'https://identity.test', 'test') RETURNING id""")
			.query(Long.class)
			.single();
		assertThat(next).isGreaterThan(id);
		assertThat(registry.nextId()).isGreaterThan(next);
	}

	@Test
	@Transactional
	void aTakenIdFails() {
		assertThatThrownBy(() -> registry.add(List.of(library(1, "같은 번호", "https://same-id.test", List.of()))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	@Transactional
	void theSameClassificationFails() {
		long id = registry.nextId();
		registry.add(List.of(library(id, "같은 분류", "https://first.test", List.of())));
		// UNIQUE NULLS NOT DISTINCT (name, vendor, lending_model, order_no), library/014
		assertThatThrownBy(() -> registry.add(List.of(library(id + 1, "같은 분류", "https://second.test", List.of()))))
			.isInstanceOf(DataIntegrityViolationException.class);
	}

	@Test
	void addNeedsTheCallersTransaction() {
		assertThatThrownBy(() -> registry.add(List.of(library(registry.nextId(), "밖", "https://outside.test",
				List.of())))).isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void nextIdUsesUpNoSequenceValue() {
		assertThat(registry.nextId()).isEqualTo(registry.nextId());
		assertThat(registry.nextId()).isGreaterThan(jdbc.sql("SELECT max(id) FROM library.library")
			.query(Long.class)
			.single());
	}

	@Test
	void listAllHasEveryLibraryInIdOrderWithTheDisabledOnes() {
		List<RegisteredLibrary> all = registry.listAll();
		assertThat(all).hasSize(jdbc.sql("SELECT count(*) FROM library.library").query(Integer.class).single());
		assertThat(all).extracting(RegisteredLibrary::id).isSorted();
		assertThat(all).filteredOn(library -> library.id() == 59 || library.id() == 131)
			.extracting(RegisteredLibrary::enabled)
			.containsExactly(false, false);
	}

	@Test
	@Transactional
	void anAddedLibraryIsInTheCatalogAtOnce() {
		long id = registry.nextId();
		registry.add(List.of(library(id, "바로 보이는 곳", "https://visible.test", List.of())));

		assertThat(catalog.enabledLibraryRefs()).extracting(LibraryRef::id).contains(id);
	}

	private static NewLibrary library(long id, String name, String baseUrl, List<NewEligibilityRule> rules) {
		return new NewLibrary(id, name, PlatformType.YES24_V2, Vendor.YES24, LendingModel.OWNED, 1, baseUrl, baseUrl,
				null, null, false, null, null, null, null, 1200, LocalDate.of(2026, 10, 12), false,
				JoinSurvey.MEMBERS_ONLY, rules);
	}

}
