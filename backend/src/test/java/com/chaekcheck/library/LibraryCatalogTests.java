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
			SEJONG = 3601, GOYANG = 4110, CHUNGNAM = 44, CHEONAN = 4401, TEST_ABOLISHED = 9001, JONGNO = 1101,
			MOKPO = 1201, GWANGJU_SEO = 1207;

	@Autowired
	LibraryCatalog catalog;

	@Autowired
	JdbcClient jdbc;

	/** Cycle 08 (AC-20, ADR-0066): the seeded phone flag reaches the plan; unresearched libraries need a choice. */
	@Test
	void planCarriesMobileSiteDiffersAndReadsUnresearchedOnlyWhenChosen() {
		List<SearchTarget> plan = catalog.planSearch(Set.of(), Set.of());
		assertThat(plan).filteredOn(SearchTarget::mobileSiteDiffers).extracting(SearchTarget::id).contains(224L, 230L);
		assertThat(plan).extracting(SearchTarget::id).doesNotContain(284L, 282L);
		assertThat(catalog.planSearch(Set.of(), Set.of(), new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.ANY,
				Set.of(284L)))).extracting(SearchTarget::id).contains(284L).doesNotContain(282L);
	}

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
			assertThat(catalog.findEnabledTarget(999_999)).isEmpty();
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
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES ('no platform', NULL, 'KYOBO', 'OWNED', 'https://no-platform.test', 'test')
				""").update())
			.hasMessageContaining("library_ebook_site_check");
	}

	@Test
	void holdingsCountNeedsItsCountDate() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method, holdings_count)
				VALUES ('no date', 'KYOBO_V1', 'KYOBO', 'OWNED', 'https://no-date.test', 'test', 10)
				""").update())
			.hasMessageContaining("library_holdings_counted_check");
	}

	@Test
	void baseUrlIsUnique() {
		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method)
				VALUES ('same url', 'KYOBO_V1', 'KYOBO', 'OWNED', 'https://www.ghlib.go.kr:446', 'test')
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

	/** Cycle 07 (ADR-0053, ADR-0059): libraries whose join rules were not researched, and the logo. */
	@Nested
	class LibrariesAddedLater {

		@Test
		void unsurveyedAreLeftOutWithoutLibraryIds() { // AC-4 server side
			onlyTestLibraries();
			long open = library("누구나", 10);
			nationwide(open);
			long members = surveyed(library("학교", 10), JoinSurvey.MEMBERS_ONLY);
			long unknown = surveyed(library("종류 모름", 10), JoinSurvey.NOT_SURVEYED);

			for (PlanOptions.Scope scope : PlanOptions.Scope.values()) {
				for (Set<Long> regions : List.of(Set.<Long>of(), Set.of(MAPO))) {
					assertThat(catalog.planSearch(regions, Set.of(), chosen(scope)))
						.as(scope + " " + regions)
						.extracting(SearchTarget::id)
						.containsExactly(open)
						.doesNotContain(members, unknown);
				}
			}
		}

		@Test
		void chosenUnsurveyedComeFirstAsUnsurveyed() { // AC-3 server side
			onlyTestLibraries();
			long open = library("누구나", 10);
			nationwide(open);
			long members = surveyed(library("학교", 10), JoinSurvey.MEMBERS_ONLY);

			for (PlanOptions.Scope scope : PlanOptions.Scope.values()) {
				assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), chosen(scope, members)))
					.as(scope.name())
					.extracting(SearchTarget::id, SearchTarget::chosen, SearchTarget::eligibility,
							SearchTarget::joinSurvey)
					.containsExactly(tuple(members, true, Eligibility.UNSURVEYED, JoinSurvey.MEMBERS_ONLY),
							tuple(open, false, Eligibility.OPEN, JoinSurvey.SURVEYED));
			}
			assertThat(catalog.findEnabledTarget(members)).get()
				.extracting(SearchTarget::eligibility, SearchTarget::joinable)
				.containsExactly(Eligibility.UNSURVEYED, false);
		}

		@Test
		void notFoundIsPlannedAsUnverifiedWithoutLibraryIds() {
			onlyTestLibraries();
			long notFound = surveyed(library("원문 없음", 10), JoinSurvey.NOT_FOUND);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::eligibility, SearchTarget::joinSurvey)
				.containsExactly(tuple(notFound, Eligibility.UNVERIFIED, JoinSurvey.NOT_FOUND));
		}

		@Test
		void surveyedLibraryAddedLaterIsJudgedByItsRegionRules() {
			onlyTestLibraries();
			long seoul = library("서울 추가", 10);
			rule(seoul, "RESIDENCE", SEOUL);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::eligibility)
				.containsExactly(tuple(seoul, Eligibility.ELIGIBLE));
			assertThat(catalog.planSearch(Set.of(HAEUNDAE), Set.of())).isEmpty();
		}

		@Test
		void listKeepsUnsurveyedLibraries() {
			onlyTestLibraries();
			long members = surveyed(library("학교", 10), JoinSurvey.NOT_SURVEYED);

			assertThat(catalog.eligibilityFor(Set.of(MAPO), Set.of()))
				.extracting(LibraryEligibility::id, LibraryEligibility::eligibility, LibraryEligibility::joinSurvey)
				.containsExactly(tuple(members, Eligibility.UNSURVEYED, JoinSurvey.NOT_SURVEYED));
		}

		@Test
		void logoUrlOnlyWhileVisible() { // AC-22 server side
			onlyTestLibraries();
			long shown = library("로고 있음", 20);
			nationwide(shown);
			long hidden = library("로고 내림", 10);
			nationwide(hidden);
			// before anything is read: the entities would otherwise stay cached in the test transaction
			jdbc.sql("UPDATE library.library SET logo_file = id || '.png' WHERE id IN (?, ?)").params(shown, hidden).update();
			jdbc.sql("UPDATE library.library SET logo_visible = false WHERE id = ?").param(hidden).update();

			assertThat(catalog.planSearch(Set.of(), Set.of())).extracting(SearchTarget::id, SearchTarget::logoUrl)
				.containsExactly(tuple(shown, "/library-logos/" + shown + ".png"), tuple(hidden, null));
			assertThat(catalog.eligibilityFor(Set.of(), Set.of())).extracting(LibraryEligibility::logoUrl)
				.containsExactly("/library-logos/" + shown + ".png", null);
			assertThat(catalog.findEnabledTarget(hidden)).get().extracting(SearchTarget::logoUrl).isNull();
		}

		@Test
		void seededLogosAreVisibleUnderTheirFileNames() {
			assertThat(catalog.findEnabledTarget(GANGSEO)).get()
				.extracting(SearchTarget::logoUrl)
				.isEqualTo("/library-logos/1.gif");
		}

		private long surveyed(long libraryId, JoinSurvey survey) {
			jdbc.sql("UPDATE library.library SET join_survey = ?, join_method = NULL WHERE id = ?")
				.params(survey.name(), libraryId)
				.update();
			return libraryId;
		}

		private static PlanOptions chosen(PlanOptions.Scope scope, Long... ids) {
			return new PlanOptions(scope, PlanOptions.Visit.ANY, Set.of(ids));
		}

	}

	/**
	 * "지역 외는 방문 가입" (user 2026-10-20): a library with its NATIONWIDE rule and REGION rules whose residents join
	 * online, everyone else only by a visit. visitRequired is the visit of the regions sent.
	 */
	@Nested
	class VisitOutsideRegion {

		static final PlanOptions NO_VISIT = new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.NONE);

		@Test
		void withoutRegionsItIsOpenWithAVisitAfterTheOpenOnes() { // (a)
			onlyTestLibraries();
			long seoulOnline = outsideVisit("서울 외 방문", 100_000, SEOUL);
			long open = library("누구나", 10);
			nationwide(open);

			assertThat(catalog.planSearch(Set.of(), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility,
						SearchTarget::visitRequired, SearchTarget::visitOutsideRegion)
				.containsExactly(tuple(open, PriorityGroup.OPEN, Eligibility.OPEN, false, false),
						tuple(seoulOnline, PriorityGroup.OPEN, Eligibility.OPEN, true, true));
		}

		@Test
		void withoutRegionsTheNoVisitFilterLeavesItOut() { // (b)
			onlyTestLibraries();
			outsideVisit("서울 외 방문", 10, SEOUL);
			long open = library("누구나", 10);
			nationwide(open);

			assertThat(catalog.planSearch(Set.of(), Set.of(), NO_VISIT)).extracting(SearchTarget::id)
				.containsExactly(open);
		}

		@Test
		void aResidentOfTheRegionIsMineWithoutAVisitAndKeptByTheNoVisitFilter() { // (c)
			onlyTestLibraries();
			long seoulOnline = outsideVisit("서울 외 방문", 10, SEOUL);
			long open = library("누구나", 100_000);
			nationwide(open);

			for (long residence : List.of(JONGNO, MAPO)) {
				assertThat(catalog.planSearch(Set.of(residence), Set.of()))
					.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility,
							SearchTarget::visitRequired, SearchTarget::visitOutsideRegion)
					.containsExactly(tuple(seoulOnline, PriorityGroup.MY_SIDO, Eligibility.ELIGIBLE, false, true),
							tuple(open, PriorityGroup.OPEN, Eligibility.OPEN, false, false));
				assertThat(catalog.planSearch(Set.of(residence), Set.of(), NO_VISIT)).extracting(SearchTarget::id)
					.containsExactly(seoulOnline, open);
			}
		}

		@Test
		void aResidentElsewhereIsOpenWithAVisitAndLeftOutByTheNoVisitFilter() { // (d)
			onlyTestLibraries();
			long seoulOnline = outsideVisit("서울 외 방문", 100_000, SEOUL);
			long open = library("누구나", 10);
			nationwide(open);

			assertThat(catalog.planSearch(Set.of(GOYANG), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::eligibility,
						SearchTarget::visitRequired)
				.containsExactly(tuple(open, PriorityGroup.OPEN, Eligibility.OPEN, false),
						tuple(seoulOnline, PriorityGroup.OPEN, Eligibility.OPEN, true));
			assertThat(catalog.planSearch(Set.of(GOYANG), Set.of(), NO_VISIT)).extracting(SearchTarget::id)
				.containsExactly(open);
		}

		@Test
		void aWorkRegionCountsOnlyForAWorkRule() {
			onlyTestLibraries();
			long residentsOnly = outsideVisit("서울 거주자만", 10, SEOUL);
			long workersToo = outsideVisit("서울 재직자도", 20, SEOUL);
			rule(workersToo, "WORK", SEOUL);

			// living in Goyang, working in Seoul: the work rule matches like "내 지역", the residence rule does not
			assertThat(catalog.planSearch(Set.of(GOYANG), Set.of(JUNG_SEOUL)))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::visitRequired)
				.containsExactly(tuple(workersToo, PriorityGroup.MY_SIDO, false),
						tuple(residentsOnly, PriorityGroup.OPEN, true));
			assertThat(catalog.planSearch(Set.of(GOYANG), Set.of(JUNG_SEOUL), NO_VISIT)).extracting(SearchTarget::id)
				.containsExactly(workersToo);
			// a work region alone, without a residence
			assertThat(catalog.planSearch(Set.of(), Set.of(JUNG_SEOUL), NO_VISIT)).extracting(SearchTarget::id)
				.containsExactly(workersToo);
		}

		@Test
		void sigunguRulesMatchOnlyTheirOwnSigungu() { // like 광주's five districts under SIDO 12
			onlyTestLibraries();
			long mapoOnline = outsideVisit("마포 외 방문", 10, MAPO);

			assertThat(catalog.planSearch(Set.of(MAPO), Set.of()))
				.extracting(SearchTarget::priorityGroup, SearchTarget::eligibility, SearchTarget::visitRequired)
				.containsExactly(tuple(PriorityGroup.MY_SIGUNGU, Eligibility.ELIGIBLE, false));
			// another district of the same SIDO is outside the region
			assertThat(catalog.planSearch(Set.of(JUNG_SEOUL), Set.of()))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup, SearchTarget::visitRequired)
				.containsExactly(tuple(mapoOnline, PriorityGroup.OPEN, true));
			assertThat(catalog.planSearch(Set.of(JUNG_SEOUL), Set.of(), NO_VISIT)).isEmpty();
		}

		@Test
		void aChosenOneStaysWithItsVisit() {
			onlyTestLibraries();
			long seoulOnline = outsideVisit("서울 외 방문", 10, SEOUL);
			PlanOptions chosenNoVisit = new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.NONE,
					Set.of(seoulOnline));

			assertThat(catalog.planSearch(Set.of(GOYANG), Set.of(), chosenNoVisit))
				.extracting(SearchTarget::id, SearchTarget::chosen, SearchTarget::visitRequired)
				.containsExactly(tuple(seoulOnline, true, true));
			assertThat(catalog.planSearch(Set.of(MAPO), Set.of(), chosenNoVisit))
				.extracting(SearchTarget::id, SearchTarget::chosen, SearchTarget::visitRequired)
				.containsExactly(tuple(seoulOnline, true, false));
		}

		@Test
		void theListAndTheSingleTargetCarryTheSameVisit() {
			onlyTestLibraries();
			long seoulOnline = outsideVisit("서울 외 방문", 10, SEOUL);

			assertThat(catalog.eligibilityFor(Set.of(MAPO), Set.of()))
				.filteredOn(row -> row.id() == seoulOnline)
				.extracting(LibraryEligibility::eligibility, LibraryEligibility::visitRequired,
						LibraryEligibility::visitOutsideRegion)
				.containsExactly(tuple(Eligibility.ELIGIBLE, false, true));
			assertThat(catalog.eligibilityFor(Set.of(GOYANG), Set.of()))
				.filteredOn(row -> row.id() == seoulOnline)
				.extracting(LibraryEligibility::eligibility, LibraryEligibility::visitRequired)
				.containsExactly(tuple(Eligibility.OPEN, true));
			assertThat(catalog.eligibilityFor(Set.of(), Set.of()))
				.filteredOn(row -> row.id() == seoulOnline)
				.extracting(LibraryEligibility::visitRequired)
				.containsExactly(true);
			// grouped as without regions
			assertThat(catalog.findEnabledTarget(seoulOnline)).get()
				.extracting(SearchTarget::priorityGroup, SearchTarget::visitRequired, SearchTarget::visitOutsideRegion)
				.containsExactly(PriorityGroup.OPEN, true, true);
		}

		/** library/018 on the seed: Seoul residents through 서울온, 광주 residents by 비대면 인증; the others visit. */
		@Test
		void seededLibrariesFollowTheRegionsSent() {
			assertThat(jdbc.sql("""
					SELECT id FROM library.library WHERE visit_outside_region AND NOT visit_required ORDER BY id""")
				.query(Long.class)
				.list()).containsExactly(60L, 61L, 133L, 223L, 230L, 277L);
			assertThat(jdbc.sql("""
					SELECT library_id || ' ' || scope_type || ' ' || coalesce(scope_region_id::text, '-')
					FROM library.eligibility_rule WHERE library_id IN (61, 277) AND basis = 'RESIDENCE'
					ORDER BY library_id, scope_type, scope_region_id""")
				.query(String.class)
				.list()).containsExactly("61 NATIONWIDE -", "61 REGION 11", "277 NATIONWIDE -", "277 REGION 1206",
						"277 REGION 1207", "277 REGION 1208", "277 REGION 1209", "277 REGION 1210");
			assertThat(jdbc.sql("SELECT eligibility_summary FROM library.library WHERE id IN (61, 277) ORDER BY id")
				.query(String.class)
				.list()).containsExactly("서울 거주", "광주 거주");

			List<Long> seeded = List.of(60L, 61L, 223L, 230L, 133L, 277L);
			assertThat(seededOf(catalog.planSearch(Set.of(), Set.of()), seeded))
				.allMatch(target -> target.priorityGroup() == PriorityGroup.OPEN && target.visitRequired())
				.hasSize(6);
			assertThat(seededOf(catalog.planSearch(Set.of(), Set.of(), NO_VISIT), seeded)).isEmpty();
			assertThat(seededOf(catalog.planSearch(Set.of(MAPO), Set.of(), NO_VISIT), seeded))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup)
				.containsExactlyInAnyOrder(tuple(60L, PriorityGroup.MY_SIDO), tuple(61L, PriorityGroup.MY_SIDO),
						tuple(223L, PriorityGroup.MY_SIDO), tuple(230L, PriorityGroup.MY_SIDO));
			assertThat(seededOf(catalog.planSearch(Set.of(GWANGJU_SEO), Set.of(), NO_VISIT), seeded))
				.extracting(SearchTarget::id, SearchTarget::priorityGroup)
				.containsExactlyInAnyOrder(tuple(133L, PriorityGroup.MY_SIGUNGU), tuple(277L, PriorityGroup.MY_SIGUNGU));
			// 목포 is in the same SIDO 12 but not one of the old 광주 districts
			assertThat(seededOf(catalog.planSearch(Set.of(MOKPO), Set.of()), seeded))
				.filteredOn(target -> target.id() == 133L || target.id() == 277L)
				.extracting(SearchTarget::priorityGroup, SearchTarget::visitRequired)
				.containsOnly(tuple(PriorityGroup.OPEN, true));
			assertThat(seededOf(catalog.planSearch(Set.of(MOKPO), Set.of(), NO_VISIT), seeded)).isEmpty();
		}

		private static List<SearchTarget> seededOf(List<SearchTarget> plan, List<Long> ids) {
			return plan.stream().filter(target -> ids.contains(target.id())).toList();
		}

		/** NATIONWIDE plus a RESIDENCE rule for {@code regionId}, residents online and the others by a visit. */
		private long outsideVisit(String name, Integer holdings, long regionId) {
			long id = library(name, holdings);
			nationwide(id);
			rule(id, "RESIDENCE", regionId);
			jdbc.sql("UPDATE library.library SET visit_outside_region = true WHERE id = ?").param(id).update();
			return id;
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
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method,
				  holdings_count, holdings_counted_on, deferred, extra_condition_text)
				VALUES (:name, 'KYOBO_V1', 'KYOBO', 'OWNED', :baseUrl, 'test', :holdings,
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
