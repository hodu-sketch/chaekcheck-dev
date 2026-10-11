package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cycle 08 Liquibase files on db-test (plan handoff step 2): the {@code updated_at} triggers (AC-6, ADR-0068),
 * {@code mobile_site_differs} (AC-20) and the national bibliography tables of the collection module.
 */
@SpringBootTest
@Transactional
class LibrarySchemaCycle08Tests {

	@Autowired
	JdbcClient jdbc;

	/** now() is the start of the test transaction, later than the seed rows' updated_at. */
	@Test
	void updateSetsUpdatedAt() {
		jdbc.sql("UPDATE library.library SET enabled = enabled WHERE id = 1").update();
		assertThat(jdbc.sql("SELECT updated_at = now() FROM library.library WHERE id = 1").query(Boolean.class).single())
			.isTrue();
		assertThat(jdbc.sql("SELECT updated_at = now() FROM library.library WHERE id = 2").query(Boolean.class).single())
			.isFalse();
		jdbc.sql("UPDATE region.region SET name = name WHERE id = 1101").update();
		assertThat(jdbc.sql("SELECT updated_at = now() FROM region.region WHERE id = 1101").query(Boolean.class).single())
			.isTrue();
	}

	@Test
	void mobileSiteDiffersOnlyForTheThirteen() {
		assertThat(jdbc.sql("SELECT id FROM library.library WHERE mobile_site_differs ORDER BY id")
			.query(Long.class)
			.list()).containsExactly(224L, 229L, 230L, 238L, 260L, 1824L, 2164L, 2190L, 2195L, 2222L, 2277L, 2533L,
					2586L);
	}

	/** AC-5: the renamed bodies (library/013, labels removed by 014), same ids, the old names gone. */
	@Test
	void renamedLibrariesKeepTheirIds() {
		assertThat(jdbc.sql("""
				SELECT id || ' ' || name FROM library.library
				WHERE id IN (434, 456, 2264, 2563, 2611, 2161, 1079, 1016) ORDER BY id""")
			.query(String.class)
			.list()).containsExactly("434 경상국립대학교", "456 국립경국대학교", "1016 국가평생교육진흥원",
					"1079 한국농수산식품유통공사", "2161 한국형사·법무정책연구원", "2264 신경주대학교", "2563 공소청 도서관",
					"2611 서울디지털콘텐츠고등학교");
		assertThat(jdbc.sql("""
				SELECT count(*) FROM library.library WHERE name IN ('경상대학교', '국립안동대학교', '서라벌대학교',
				  '대검 도서관', '강서공업고등학교', '한국형사정책연구원', '농수산물유통공사', '국가평가교육진흥원')""")
			.query(Long.class)
			.single()).isZero();
	}

	@Test
	void nationalBibliographyTablesHaveNoIndexBesidesTheKey() {
		assertThat(jdbc.sql("""
				SELECT indexname FROM pg_indexes WHERE schemaname = 'collection'
				  AND tablename IN ('national_bibliography_record', 'national_bibliography_source')""")
			.query(String.class)
			.list()).containsExactly("national_bibliography_record_pkey");
	}

}
