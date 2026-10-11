package com.chaekcheck.collection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cycle 07 Liquibase files on db-test (plan handoff step 2): the candidate seed, the links, the new columns and
 * their CHECK constraints. Counts come from scripts/library/build_candidate_seed.py (docs/library-maintenance.md 13절).
 * Each violation runs in its own test so the failed statement does not abort the others.
 */
@SpringBootTest
@Transactional
class CandidateSeedTests {

	static final int REGISTERED = 280, CANDIDATES = 2383;

	@Autowired
	JdbcClient jdbc;

	@Test
	void seedCountsMatchTheScript() {
		assertThat(count("SELECT count(*) FROM library.library WHERE id <= 280")).isEqualTo(REGISTERED);
		// cycle 08 (ADR-0067): the 11 Kyobo rental and mixed candidates 2664~2674 left the seed; their ids are not reused
		assertThat(count("SELECT count(*) FROM library.library WHERE id > 280")).isEqualTo(CANDIDATES);
		assertThat(count("SELECT count(*) FROM library.library WHERE id BETWEEN 2664 AND 2674")).isZero();
		assertThat(count("SELECT count(*) FROM collection.collection_link WHERE library_id BETWEEN 2664 AND 2674"))
			.isZero();
		assertThat(count("SELECT count(*) FROM collection.collection_link")).isEqualTo(REGISTERED + CANDIDATES);
		assertThat(count("SELECT count(*) FROM collection.collection_link WHERE collection_library_id = library_id"))
			.isEqualTo(REGISTERED);
		assertThat(count("SELECT count(*) FROM library.eligibility_rule WHERE library_id > 280")).isEqualTo(253);
		assertThat(jdbc.sql("""
				SELECT join_survey, count(*) FROM library.library WHERE id > 280 GROUP BY join_survey""")
			.query((rs, n) -> Map.entry(rs.getString(1), rs.getLong(2)))
			.list()).containsExactlyInAnyOrder(Map.entry("MEMBERS_ONLY", 2241L), Map.entry("SURVEYED", 90L),
					Map.entry("NOT_SURVEYED", 35L), Map.entry("NOT_FOUND", 17L));
	}

	@Test
	void registeredLibrariesAreSurveyedAndKeepTheirValues() {
		assertThat(count("SELECT count(*) FROM library.library WHERE id <= 280 AND join_survey <> 'SURVEYED'"))
			.isZero();
		assertThat(count("SELECT count(*) FROM library.library WHERE id <= 280 AND join_method IS NULL")).isZero();
		assertThat(jdbc.sql("SELECT lending_model FROM library.library WHERE id = 19").query(String.class).single())
			.isEqualTo("OWNED");
		// library/014: 5 KYOBO subscription rows from the probe (library/008) + 250 rows of the other non-portal
		// screens (counted on the db after 014, 2026-10-11)
		assertThat(count("SELECT count(*) FROM library.library WHERE id <= 280 AND lending_model = 'OWNED'"))
			.isEqualTo(255);
		assertThat(count("SELECT count(*) FROM library.library WHERE logo_file IS NOT NULL")).isEqualTo(198);
	}

	/** Sequences are not rolled back with the test transactions, so other tests may have used numbers already. */
	@Test
	void identityContinuesAfterTheSeed() {
		long id = jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES ('새 도서관', 'KYOBO_V1', 'KYOBO', 'OWNED', 'https://new.test', 'test') RETURNING id""")
			.query(Long.class)
			.single();
		assertThat(id).isGreaterThan(REGISTERED + CANDIDATES);
	}

	@Test
	void joinSurveyHasFourValues() {
		assertViolation("UPDATE library.library SET join_survey = 'OTHER' WHERE id = 1");
	}

	@Test
	void surveyedNeedsAJoinMethod() {
		assertViolation("UPDATE library.library SET join_method = NULL WHERE id = 1");
	}

	@Test
	void unsurveyedMayHaveNoJoinMethod() {
		jdbc.sql("UPDATE library.library SET join_survey = 'NOT_SURVEYED', join_method = NULL WHERE id = 1").update();
	}

	@Test
	void platformTypeAllowsTheNewScreens() {
		jdbc.sql("UPDATE library.library SET platform_type = 'ALADIN_V1' WHERE id = 1").update();
		assertViolation("UPDATE library.library SET platform_type = 'OTHER_V1' WHERE id = 2");
	}

	@Test
	void lendingModelHasFourValues() {
		assertViolation("UPDATE library.library SET lending_model = 'LEASED' WHERE id = 1");
	}

	@Test
	void linkIdsArePositive() {
		assertViolation("INSERT INTO collection.collection_link (collection_library_id, library_id) VALUES (0, 9999)");
	}

	@Test
	void aServiceLibraryHasOneLink() {
		assertViolation("INSERT INTO collection.collection_link (collection_library_id, library_id) VALUES (999999, 1)");
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	private void assertViolation(String sql) {
		assertThatThrownBy(() -> jdbc.sql(sql).update()).isInstanceOf(DataIntegrityViolationException.class);
	}

}
