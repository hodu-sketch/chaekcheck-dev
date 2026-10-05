package com.chaekcheck.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.region.RegionNotUsableException.Reason;
import com.chaekcheck.region.RegionResolution.Replacement;
import com.chaekcheck.region.RegionResolution.Status;

/**
 * Runs against the db-test database with the real seed and the test-data context
 * (see db/changelog/region/900-region-test-abolished.yaml).
 */
@ApplicationModuleTest
@Transactional
class RegionDirectoryTests {

	static final long SEOUL = 11, JONGNO = 1101, JUNG_SEOUL = 1102, MAPO = 1114, GANGSEO_SEOUL = 1116;

	static final long TEST_REPLACED = 9001, TEST_SPLIT = 9002, TEST_CHAIN_START = 9003, TEST_CHAIN_MIDDLE = 9004;

	@Autowired
	RegionDirectory directory;

	@Autowired
	JdbcClient jdbc;

	@Test
	void seedHasAllSidoAndSigungu() {
		assertThat(countActive("SIDO")).isEqualTo(16);
		// 233 since 증평군 was added (2026-10-05)
		assertThat(countActive("SIGUNGU")).isEqualTo(233);
	}

	@Test
	void replacementLinksHaveNoCycle() {
		Integer cycles = jdbc.sql("""
				WITH RECURSIVE walk(start_id, current_id, path) AS (
				  SELECT region_id, replaced_by_id, ARRAY[region_id] FROM region.region_replacement
				  UNION ALL
				  SELECT w.start_id, r.replaced_by_id, w.path || w.current_id
				  FROM walk w JOIN region.region_replacement r ON r.region_id = w.current_id
				  WHERE NOT w.current_id = ANY(w.path)
				)
				SELECT count(*) FROM walk WHERE current_id = start_id
				""").query(Integer.class).single();
		assertThat(cycles).isZero();
	}

	@Test
	void expandsSigunguToItselfAndItsSido() {
		assertThat(directory.expandForMatching(Set.of(MAPO, JUNG_SEOUL)))
			.isEqualTo(Map.of(MAPO, Set.of(MAPO, SEOUL), JUNG_SEOUL, Set.of(JUNG_SEOUL, SEOUL)));
	}

	@Test
	void expandRejectsAbolishedUnknownAndSidoIds() {
		assertThatThrownBy(() -> directory.expandForMatching(Set.of(MAPO, TEST_REPLACED, 12345L, SEOUL)))
			.isInstanceOfSatisfying(RegionNotUsableException.class,
					e -> assertThat(e.reasons()).isEqualTo(Map.of(TEST_REPLACED, Reason.ABOLISHED, 12345L,
							Reason.NOT_FOUND, SEOUL, Reason.NOT_SIGUNGU)));
	}

	@Test
	void resolvesActive() {
		assertThat(directory.resolve(Set.of(GANGSEO_SEOUL)))
			.containsExactly(new RegionResolution(GANGSEO_SEOUL, Status.ACTIVE, "서울 강서구", List.of()));
	}

	@Test
	void resolvesReplaced() {
		assertThat(directory.resolve(Set.of(TEST_REPLACED))).containsExactly(new RegionResolution(TEST_REPLACED,
				Status.REPLACED, "테스트 대체구", List.of(new Replacement(GANGSEO_SEOUL, "서울 강서구"))));
	}

	@Test
	void resolvesSplit() {
		assertThat(directory.resolve(Set.of(TEST_SPLIT))).containsExactly(new RegionResolution(TEST_SPLIT, Status.SPLIT,
				"테스트 분할군", List.of(new Replacement(MAPO, "서울 마포구"), new Replacement(GANGSEO_SEOUL, "서울 강서구"))));
	}

	@Test
	void followsReplacementChainToTheEnd() {
		assertThat(directory.resolve(Set.of(TEST_CHAIN_START))).singleElement().satisfies(r -> {
			assertThat(r.status()).isEqualTo(Status.REPLACED);
			assertThat(r.replacements()).containsExactly(new Replacement(JONGNO, "서울 종로구"));
		});
	}

	@Test
	void resolvesUnknownIdsAndSidoIdsAsUnknown() {
		assertThat(directory.resolve(Set.of(12345L, SEOUL))).extracting(RegionResolution::status)
			.containsExactly(Status.UNKNOWN, Status.UNKNOWN);
	}

	@Test
	void abolishedWithoutReplacementIsUnknown() {
		jdbc.sql("DELETE FROM region.region_replacement WHERE region_id = ?").param(TEST_REPLACED).update();
		assertThat(directory.resolve(Set.of(TEST_REPLACED))).singleElement()
			.extracting(RegionResolution::status)
			.isEqualTo(Status.UNKNOWN);
	}

	@Test
	void cycleInReplacementLinksStillTerminates() {
		jdbc.sql("INSERT INTO region.region_replacement (region_id, replaced_by_id, effective_on) VALUES (?, ?, DATE '2026-04-01')")
			.params(TEST_CHAIN_MIDDLE, TEST_CHAIN_START)
			.update();
		assertThat(directory.resolve(Set.of(TEST_CHAIN_START))).singleElement()
			.extracting(RegionResolution::replacements)
			.isEqualTo(List.of(new Replacement(JONGNO, "서울 종로구")));
	}

	@Test
	void resultIsOrderedById() {
		assertThat(directory.resolve(Set.of(MAPO, TEST_REPLACED, JONGNO))).extracting(RegionResolution::id)
			.containsExactly(JONGNO, MAPO, TEST_REPLACED);
	}

	private int countActive(String level) {
		return jdbc.sql("SELECT count(*) FROM region.region WHERE status = 'ACTIVE' AND level = ?")
			.param(level)
			.query(Integer.class)
			.single();
	}

}
