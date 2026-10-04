package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest.BootstrapMode;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.library.InvalidRegionException.InvalidRegion;
import com.chaekcheck.library.InvalidRegionException.Reason;

/**
 * Runs against db-test with the real region and library seeds. Library 1 = Gangseo (Seoul residents,
 * workers and students), library 2 = Goheung (open to anyone: a NATIONWIDE rule since cycle 02).
 * Plan-order tests switch every seeded library off inside the test transaction and insert their own rows, so
 * they do not depend on the seed (which grows in step 11). Region ids come from region/002-region-seed.csv.
 */
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@Transactional
class LibraryCatalogTests {

	static final long GANGSEO = 1, GOHEUNG = 2;

	static final long SEOUL = 11, JUNG_SEOUL = 1102, MAPO = 1114, BUSAN = 26, HAEUNDAE = 2609, GEUMJEONG = 2611,
			SEJONG = 3601, GOYANG = 4110, CHUNGNAM = 44, CHEONAN = 4401, TEST_ABOLISHED = 9001;

	@Autowired
	LibraryCatalog catalog;

	@Autowired
	JdbcClient jdbc;

	@Nested
	class PilotLibraries {

		@Test
		void withoutRegionsOpenComesBeforeQualified() { // cycle 01 AC-2 with cycle 02 order
			assertThat(pilots(catalog.planSearch(Set.of(), Set.of())))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility,
						SearchTarget::eligibilitySummary)
				.containsExactly(tuple(GOHEUNG, PriorityGroup.OPEN, Eligibility.OPEN, null),
						tuple(GANGSEO, PriorityGroup.QUALIFIED, Eligibility.UNKNOWN, "서울 거주·재직·재학"));
		}

		@Test
		void seoulResidentGetsGangseoAsMySido() {
			assertThat(pilots(catalog.planSearch(Set.of(MAPO), Set.of())))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility)
				.containsExactly(tuple(GANGSEO, PriorityGroup.MY_SIDO, Eligibility.ELIGIBLE),
						tuple(GOHEUNG, PriorityGroup.OPEN, Eligibility.OPEN));
		}

		@Test
		void busanResidentGetsOnlyOpenLibrary() {
			assertThat(pilots(catalog.planSearch(Set.of(HAEUNDAE), Set.of()))).extracting(SearchTarget::id)
				.containsExactly(GOHEUNG);
		}

		@Test
		void workRegionAloneCanMakeUserEligible() {
			assertThat(pilots(catalog.planSearch(Set.of(GOYANG), Set.of(JUNG_SEOUL))))
				.extracting(SearchTarget::id, SearchTarget::eligibility)
				.containsExactly(tuple(GANGSEO, Eligibility.ELIGIBLE), tuple(GOHEUNG, Eligibility.OPEN));
		}

		@Test
		void schoolRulesAreNotCompared() {
			assertThat(pilots(catalog.planSearch(Set.of(HAEUNDAE), Set.of(HAEUNDAE)))).extracting(SearchTarget::id)
				.containsExactly(GOHEUNG);
		}

		@Test
		void disabledLibraryIsNeverATarget() {
			jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(GANGSEO).update();

			assertThat(pilots(catalog.planSearch(Set.of(), Set.of()))).extracting(SearchTarget::id)
				.containsExactly(GOHEUNG);
			assertThat(pilots(catalog.planSearch(Set.of(MAPO), Set.of()))).extracting(SearchTarget::id)
				.containsExactly(GOHEUNG);
			assertThat(catalog.findEnabledTarget(GANGSEO)).isEmpty();
		}

		@Test
		void findsEnabledTargetGroupedWithoutRegions() {
			assertThat(catalog.findEnabledTarget(GOHEUNG)).get()
				.extracting(SearchTarget::platformType, SearchTarget::baseUrl, SearchTarget::libraryType,
						SearchTarget::priorityGroup)
				.containsExactly(PlatformType.KYOBO_V1, "https://www.ghlib.go.kr:446", LibraryType.EBOOK,
						PriorityGroup.OPEN);
			assertThat(catalog.findEnabledTarget(GANGSEO)).get()
				.extracting(SearchTarget::priorityGroup, SearchTarget::eligibility)
				.containsExactly(PriorityGroup.QUALIFIED, Eligibility.UNKNOWN);
			assertThat(catalog.findEnabledTarget(999)).isEmpty();
		}

		private List<SearchTarget> pilots(List<SearchTarget> plan) {
			return plan.stream().filter(target -> target.id() == GANGSEO || target.id() == GOHEUNG).toList();
		}

	}

	@Nested
	class PlanOrder {

		@Test
		void withoutRegionsOpenThenQualifiedThenNeedsCheck() { // AC-1
			onlyTestLibraries();
			long unverified = library("미확인", 900);
			long qualified = library("지역 규칙", 800);
			rule(qualified, "RESIDENCE", CHUNGNAM);
			long open = library("누구나", 10);
			nationwide(open);

			assertThat(catalog.planSearch(Set.of(), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility)
				.containsExactly(tuple(open, PriorityGroup.OPEN, Eligibility.OPEN),
						tuple(qualified, PriorityGroup.QUALIFIED, Eligibility.UNKNOWN),
						tuple(unverified, PriorityGroup.NEEDS_CHECK, Eligibility.UNVERIFIED));
		}

		@Test
		void insideAGroupDeferredLastThenHoldingsDescWithUnknownLastThenId() { // AC-8
			onlyTestLibraries();
			long deferredBig = library("후순위 큰 곳", 100_000, true, null);
			long noCount = library("보유 모름", null, false, null);
			long small = library("작은 곳", 100, false, null);
			long big = library("큰 곳", 5_000, false, null);
			long sameAsSmall = library("작은 곳 둘", 100, false, null);
			for (long id : List.of(deferredBig, noCount, small, big, sameAsSmall)) {
				nationwide(id);
			}

			assertThat(catalog.planSearch(Set.of(), Set.of())).extracting(SearchTarget::id)
				.containsExactly(big, small, sameAsSmall, noCount, deferredBig);
		}

		@Test
		void visitOnlyLibrariesComeAfterTheRestOfTheirGroup() { // user decision 2026-09-29
			onlyTestLibraries();
			long visitBig = library("방문 큰 곳", 100_000);
			long deferredSmall = library("후순위 작은 곳", 10, true, null);
			long plainSmall = library("방문 없는 작은 곳", 100);
			long qualified = library("자격 필요", 50_000);
			jdbc.sql("UPDATE library.library SET visit_required = true WHERE id = ?").param(visitBig).update();
			nationwide(visitBig);
			nationwide(deferredSmall);
			nationwide(plainSmall);
			rule(qualified, "RESIDENCE", SEOUL);

			// without regions: anyone without a visit (deferred last among them), anyone with a visit, then qualified
			assertThat(catalog.planSearch(Set.of(), Set.of())).extracting(SearchTarget::id)
				.containsExactly(plainSmall, deferredSmall, visitBig, qualified);
			// with regions: every joinable group without a visit first, then the visit ones (ADR-0027)
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of())).extracting(SearchTarget::id)
				.containsExactly(qualified, plainSmall, deferredSmall, visitBig);
		}

		@Test
		void withRegionsVisitOnlyLibrariesComeAfterEveryJoinableGroup() { // ADR-0027
			onlyTestLibraries();
			long sigunguVisit = library("마포 방문", 100_000);
			long sidoVisit = library("서울 방문", 90_000);
			long openVisit = library("누구나 방문", 80_000);
			long sigungu = library("마포", 10);
			long sido = library("서울", 10);
			long open = library("누구나", 10);
			long unverifiedVisit = library("미확인 방문", 70_000);
			long unverified = library("미확인", 10);
			jdbc.sql("UPDATE library.library SET visit_required = true WHERE id IN (?, ?, ?, ?)")
				.params(sigunguVisit, sidoVisit, openVisit, unverifiedVisit)
				.update();
			rule(sigunguVisit, "RESIDENCE", MAPO);
			rule(sigungu, "RESIDENCE", MAPO);
			rule(sidoVisit, "RESIDENCE", SEOUL);
			rule(sido, "RESIDENCE", SEOUL);
			nationwide(openVisit);
			nationwide(open);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of())).extracting(SearchTarget::id)
				.containsExactly(sigungu, sido, open, sigunguVisit, sidoVisit, openVisit, unverified, unverifiedVisit);
		}

		@Test
		void planOptionsKeepOnlyJoinableAndNoVisitLibraries() { // cycle 04 PlanOptions = cycle 02/03 scope, visit
			onlyTestLibraries();
			long openVisit = library("누구나 방문", 10);
			long open = library("누구나", 10);
			long sido = library("서울", 10);
			long unverified = library("미확인", 10);
			jdbc.sql("UPDATE library.library SET visit_required = true WHERE id = ?").param(openVisit).update();
			nationwide(openVisit);
			nationwide(open);
			rule(sido, "RESIDENCE", SEOUL);
			PlanOptions joinable = new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.ANY);
			PlanOptions noVisit = new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.NONE);
			PlanOptions both = new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.NONE);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), PlanOptions.DEFAULT)).extracting(SearchTarget::id)
				.containsExactly(sido, open, openVisit, unverified);
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), joinable)).extracting(SearchTarget::id)
				.containsExactly(sido, open, openVisit);
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), noVisit)).extracting(SearchTarget::id)
				.containsExactly(sido, open, unverified);
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), both)).extracting(SearchTarget::id)
				.containsExactly(sido, open);
			// without regions the Seoul library needs a check, so it is not joinable
			assertThat(catalog.planSearch(Set.of(), Set.of(), joinable)).extracting(SearchTarget::id)
				.containsExactly(open, openVisit);
			assertThat(catalog.planSearch(Set.of(), Set.of(), both)).extracting(SearchTarget::id).containsExactly(open);
		}

		@Test
		void enabledLibraryRefsLeaveOutDisabledLibraries() {
			onlyTestLibraries();
			long on = library("켜짐", 1);
			long off = library("꺼짐", 2);
			jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(off).update();

			assertThat(catalog.enabledLibraryRefs()).extracting(LibraryRef::id).containsExactly(on);
			assertThat(catalog.enabledLibraryRefs()).first()
				.extracting(LibraryRef::platformType)
				.isEqualTo(PlatformType.KYOBO_V1);
		}

		@Test
		void insideAGroupLibrariesWithoutACautionComeFirst() { // C2-U-11
			onlyTestLibraries();
			long cautionedBig = library("주의 큰 곳", 100_000);
			long plainSmall = library("주의 없는 작은 곳", 100);
			jdbc.sql("UPDATE library.library SET join_caution = '회원증은 주민만' WHERE id = ?")
				.param(cautionedBig)
				.update();
			nationwide(cautionedBig);
			nationwide(plainSmall);

			assertThat(catalog.planSearch(Set.of(), Set.of())).extracting(SearchTarget::id)
				.containsExactly(plainSmall, cautionedBig);
		}

		@Test
		void withRegionsSigunguThenSidoThenOpenThenNeedsCheck() { // AC-9
			onlyTestLibraries();
			long unverified = library("미확인", 9_000);
			long open = library("누구나", 8_000);
			nationwide(open);
			long sido = library("서울 규칙", 7_000);
			rule(sido, "RESIDENCE", SEOUL);
			long sigungu = library("마포 규칙", 6_000);
			rule(sigungu, "RESIDENCE", MAPO);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility)
				.containsExactly(tuple(sigungu, PriorityGroup.MY_SIGUNGU, Eligibility.ELIGIBLE),
						tuple(sido, PriorityGroup.MY_SIDO, Eligibility.ELIGIBLE),
						tuple(open, PriorityGroup.OPEN, Eligibility.OPEN),
						tuple(unverified, PriorityGroup.NEEDS_CHECK, Eligibility.UNVERIFIED));
		}

		@Test
		void nonMatchingRegionLibraryIsLeftOutAndUnverifiedStays() { // AC-10
			onlyTestLibraries();
			long busanOnly = library("부산 규칙", 1);
			rule(busanOnly, "RESIDENCE", BUSAN);
			long unverified = library("미확인", 1);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::eligibility)
				.containsExactly(tuple(unverified, Eligibility.UNVERIFIED));
		}

		@Test
		void sejongResidentCanJoinCheonanWithSeveralNeighbourRules() { // AC-11
			onlyTestLibraries();
			long cheonan = library("천안", 1);
			rule(cheonan, "RESIDENCE", CHEONAN);
			rule(cheonan, "RESIDENCE", SEJONG);
			rule(cheonan, "WORK", CHEONAN);

			assertThat(catalog.planSearch(Set.of(SEJONG), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility)
				.containsExactly(tuple(cheonan, PriorityGroup.MY_SIGUNGU, Eligibility.ELIGIBLE));
		}

		@Test
		void regionRulePlusUndecidableConditionLikeGeumjeong() { // AC-12
			onlyTestLibraries();
			String text = "책이음 회원 가입 가능";
			long geumjeong = library("금정", 1, false, text);
			rule(geumjeong, "RESIDENCE", GEUMJEONG);

			assertThat(catalog.planSearch(Set.of(GEUMJEONG), Set.of()))
				.extracting(SearchTarget::priorityGroup, SearchTarget::eligibility, SearchTarget::extraConditionText)
				.containsExactly(tuple(PriorityGroup.MY_SIGUNGU, Eligibility.ELIGIBLE, text));
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::priorityGroup, SearchTarget::eligibility, SearchTarget::extraConditionText)
				.containsExactly(tuple(PriorityGroup.NEEDS_CHECK, Eligibility.UNVERIFIED, text));
			assertThat(catalog.planSearch(Set.of(), Set.of()))
				.extracting(SearchTarget::priorityGroup, SearchTarget::eligibility, SearchTarget::extraConditionText)
				.containsExactly(tuple(PriorityGroup.QUALIFIED, Eligibility.UNKNOWN, text));
		}

		@Test
		void severalMatchingRulesUseTheEarliestGroup() {
			onlyTestLibraries();
			long both = library("시군구와 시도", 1);
			rule(both, "RESIDENCE", SEOUL);
			rule(both, "WORK", MAPO);
			long sidoAndNationwide = library("시도와 누구나", 1);
			rule(sidoAndNationwide, "RESIDENCE", SEOUL);
			nationwide(sidoAndNationwide);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(MAPO)))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup)
				.containsExactly(tuple(both, PriorityGroup.MY_SIGUNGU),
						tuple(sidoAndNationwide, PriorityGroup.MY_SIDO));
		}

		@Test
		void disabledTestLibraryIsLeftOut() {
			onlyTestLibraries();
			long on = library("켜짐", 1);
			long off = library("꺼짐", 2);
			jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(off).update();

			assertThat(catalog.planSearch(Set.of(), Set.of())).extracting(SearchTarget::id).containsExactly(on);
		}

	}

	@Test
	void invalidRegionsNameTheFieldAndReason() {
		assertThatThrownBy(() -> catalog.planSearch(Set.of(MAPO, TEST_ABOLISHED), Set.of(SEOUL, 12345L)))
			.isInstanceOfSatisfying(InvalidRegionException.class,
					e -> assertThat(e.invalidRegions()).containsExactly(
							new InvalidRegion("residenceRegionIds", TEST_ABOLISHED, Reason.ABOLISHED),
							new InvalidRegion("workRegionIds", SEOUL, Reason.NOT_SIGUNGU),
							new InvalidRegion("workRegionIds", 12345L, Reason.NOT_FOUND)));
	}

	@Test
	void seedDoesNotBlockNextGeneratedLibraryId() {
		// sequences are not rolled back with the test transaction, so only "after the seeded ids" is stable
		assertThat(library("test", null)).isGreaterThan(GOHEUNG);
	}

	@Test
	void nationwideRuleCannotNameARegion() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.eligibility_rule (library_id, basis, scope_type, scope_region_id)
				VALUES (?, 'RESIDENCE', 'NATIONWIDE', ?)
				""").params(GANGSEO, SEOUL).update())
			.hasMessageContaining("eligibility_rule_scope_check");
	}

	@Test
	void ebookLibraryNeedsPlatformTypeAndBaseUrl() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method)
				VALUES ('no platform', NULL, 'https://no-platform.test', 'test')
				""").update())
			.hasMessageContaining("library_ebook_site_check");
	}

	@Test
	void holdingsCountNeedsItsCountDate() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method, holdings_count)
				VALUES ('no date', 'KYOBO_V1', 'https://no-date.test', 'test', 10)
				""").update())
			.hasMessageContaining("library_holdings_counted_check");
	}

	@Test
	void baseUrlIsUnique() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method)
				VALUES ('same url', 'KYOBO_V1', 'https://www.ghlib.go.kr:446', 'test')
				""").update())
			.hasMessageContaining("library_base_url_key");
	}

	/** Cycle 06, ADR-0050: libraries the user added by hand ({@code libraryIds}). */
	@Nested
	class ChosenLibraries {

		@Test
		void libraryLeftOutByRegionsComesBackChosenAndUnknown() {
			onlyTestLibraries();
			long open = library("누구나", 10);
			nationwide(open);
			long busan = library("부산 규칙", 10);
			rule(busan, "RESIDENCE", BUSAN);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of())).extracting(SearchTarget::id).containsExactly(open);
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), chosen(PlanOptions.Scope.ALL, busan)))
				.extracting(SearchTarget::id, SearchTarget::chosen, SearchTarget::eligibility,
						SearchTarget::priorityGroup)
				.containsExactly(tuple(busan, true, Eligibility.UNKNOWN, PriorityGroup.QUALIFIED),
						tuple(open, false, Eligibility.OPEN, PriorityGroup.OPEN));
		}

		@Test
		void joinableScopeAndNoVisitKeepChosenLibraries() {
			onlyTestLibraries();
			long open = library("누구나", 10);
			nationwide(open);
			long unverified = library("미확인", 10);
			long visit = library("방문 가입", 10);
			nationwide(visit);
			jdbc.sql("UPDATE library.library SET visit_required = true WHERE id = ?").param(visit).update();
			PlanOptions both = new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.NONE,
					Set.of(unverified, visit));

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(),
					new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.NONE)))
				.extracting(SearchTarget::id)
				.containsExactly(open);
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), both)).extracting(SearchTarget::id, SearchTarget::chosen)
				.containsExactly(tuple(unverified, true), tuple(visit, true), tuple(open, false));
		}

		@Test
		void chosenComeFirstInBothScopesAndKeepTheirGroup() {
			onlyTestLibraries();
			long sigungu = library("마포 규칙", 10);
			rule(sigungu, "RESIDENCE", MAPO);
			long open = library("누구나", 10);
			nationwide(open);
			long unverifiedSmall = library("미확인 작은 곳", 5);
			long unverifiedBig = library("미확인 큰 곳", 500);

			for (PlanOptions.Scope scope : PlanOptions.Scope.values()) {
				assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), chosen(scope, open, unverifiedSmall)))
					.as(scope.name())
					.extracting(SearchTarget::id, SearchTarget::chosen, SearchTarget::priorityGroup)
					.startsWith(tuple(open, true, PriorityGroup.OPEN),
							tuple(unverifiedSmall, true, PriorityGroup.NEEDS_CHECK),
							tuple(sigungu, false, PriorityGroup.MY_SIGUNGU));
			}
			// inside the chosen block the usual in-group order applies (holdings count descending)
			assertThat(catalog.planSearch(Set.of(), Set.of(), chosen(PlanOptions.Scope.ALL, unverifiedSmall, unverifiedBig)))
				.extracting(SearchTarget::id)
				.startsWith(unverifiedBig, unverifiedSmall);
		}

		@Test
		void disabledAndUnknownIdsAreIgnored() { // AC-10
			onlyTestLibraries();
			long open = library("누구나", 10);
			nationwide(open);
			long off = library("꺼짐", 10);
			jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(off).update();

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), chosen(PlanOptions.Scope.ALL, off, 999_999L)))
				.extracting(SearchTarget::id, SearchTarget::chosen)
				.containsExactly(tuple(open, false));
		}

		@Test
		void withoutLibraryIdsThePlanIsUnchanged() {
			List<SearchTarget> plain = catalog.planSearch(Set.of(MAPO), Set.of(JUNG_SEOUL));

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(JUNG_SEOUL),
					new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.ANY, Set.of())))
				.isEqualTo(plain)
				.noneMatch(SearchTarget::chosen);
		}

		@Test
		void findEnabledTargetIsNeverChosen() {
			assertThat(catalog.findEnabledTarget(GOHEUNG)).get().extracting(SearchTarget::chosen).isEqualTo(false);
		}

		private static PlanOptions chosen(PlanOptions.Scope scope, Long... ids) {
			return new PlanOptions(scope, PlanOptions.Visit.ANY, Set.of(ids));
		}

	}

	private void onlyTestLibraries() {
		jdbc.sql("UPDATE library.library SET enabled = false").update();
	}

	private long library(String name, Integer holdings) {
		return library(name, holdings, false, null);
	}

	private long library(String name, Integer holdings, boolean deferred, String extraConditionText) {
		return jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method, holdings_count,
				  holdings_counted_on, deferred, extra_condition_text)
				VALUES (:name, 'KYOBO_V1', :baseUrl, 'test', :holdings,
				  CASE WHEN CAST(:holdings AS integer) IS NULL THEN NULL ELSE DATE '2026-09-28' END, :deferred, :extra)
				RETURNING id
				""")
			.param("name", "테스트 " + name)
			.param("baseUrl", "https://" + Integer.toHexString(name.hashCode()) + ".test")
			.param("holdings", holdings)
			.param("deferred", deferred)
			.param("extra", extraConditionText)
			.query(Long.class)
			.single();
	}

	private void rule(long libraryId, String basis, long regionId) {
		jdbc.sql("""
				INSERT INTO library.eligibility_rule (library_id, basis, scope_type, scope_region_id)
				VALUES (?, ?, 'REGION', ?)
				""").params(libraryId, basis, regionId).update();
	}

	private void nationwide(long libraryId) {
		jdbc.sql("""
				INSERT INTO library.eligibility_rule (library_id, basis, scope_type)
				VALUES (?, 'RESIDENCE', 'NATIONWIDE')
				""").param(libraryId).update();
	}

}
