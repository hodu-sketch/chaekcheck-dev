package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cycle 06 step 3: {@code GET /api/v1/libraries} (ADR-0049) against db-test. Every test switches the seeded
 * libraries off inside its transaction and inserts its own, so nothing depends on the seed. Region ids come from
 * region/002-region-seed.csv.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LibraryListTests {

	static final long SEOUL = 11, JUNG_SEOUL = 1102, MAPO = 1114, BUSAN = 26, HAEUNDAE = 2609, TEST_ABOLISHED = 9001;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	long open, seoulAll, busanResidence, unverified, off;

	@BeforeEach
	void setUp() {
		jdbc.sql("UPDATE library.library SET enabled = false").update();
		open = library("누구나", null, false);
		rule(open, "RESIDENCE", "NATIONWIDE", null);
		seoulAll = library("서울", "서울 거주·재직·재학", false);
		rule(seoulAll, "RESIDENCE", "REGION", SEOUL);
		rule(seoulAll, "WORK", "REGION", SEOUL);
		busanResidence = library("부산", "부산 거주", true);
		rule(busanResidence, "RESIDENCE", "REGION", BUSAN);
		jdbc.sql("UPDATE library.library SET join_caution = '회원증은 주민만' WHERE id = ?").param(busanResidence).update();
		unverified = library("미확인", null, false);
		off = library("꺼짐", null, false);
		rule(off, "RESIDENCE", "NATIONWIDE", null);
		jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(off).update();
	}

	@Test
	void withoutRegionsOpenHasNoMatchAndDisabledIsLeftOut() {
		assertThat(get("")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"libraries":[
				  {"id":%d,"name":"테스트 누구나","eligibility":"OPEN","matchedBy":[],"visitRequired":false,
				   "cautioned":false,"eligibilitySummary":null,"joinSurvey":"SURVEYED","homepageUrl":null,"logoUrl":null,"mobileSiteDiffers":false,
				   "vendor":"KYOBO","lendingModel":"OWNED","orderNo":1,"visitOutsideRegion":false},
				  {"id":%d,"name":"테스트 서울","eligibility":"UNKNOWN","matchedBy":[],"visitRequired":false,
				   "cautioned":false,"eligibilitySummary":"서울 거주·재직·재학","joinSurvey":"SURVEYED","homepageUrl":null,
				   "logoUrl":null,"mobileSiteDiffers":false,
				   "vendor":"KYOBO","lendingModel":"OWNED","orderNo":1,"visitOutsideRegion":false},
				  {"id":%d,"name":"테스트 부산","eligibility":"UNKNOWN","matchedBy":[],"visitRequired":true,
				   "cautioned":true,"eligibilitySummary":"부산 거주","joinSurvey":"SURVEYED","homepageUrl":null,"logoUrl":null,"mobileSiteDiffers":false,
				   "vendor":"KYOBO","lendingModel":"OWNED","orderNo":1,"visitOutsideRegion":false},
				  {"id":%d,"name":"테스트 미확인","eligibility":"UNVERIFIED","matchedBy":[],"visitRequired":false,
				   "cautioned":false,"eligibilitySummary":null,"joinSurvey":"SURVEYED","homepageUrl":null,"logoUrl":null,"mobileSiteDiffers":false,
				   "vendor":"KYOBO","lendingModel":"OWNED","orderNo":1,"visitOutsideRegion":false}
				]}
				""".formatted(open, seoulAll, busanResidence, unverified));
	}

	/**
	 * Cycle 09 (ADR-0076, AC-9 API side): vendor, lendingModel and orderNo come with the list; a portal has no
	 * lendingModel.
	 */
	@Test
	void classificationIsListed() {
		jdbc.sql("UPDATE library.library SET order_no = 2 WHERE id = ?").param(seoulAll).update();
		jdbc.sql("""
				UPDATE library.library SET platform_type = 'ELIB_PORTAL_V1', vendor = 'PORTAL', lending_model = NULL
				WHERE id = ?""").param(busanResidence).update();
		assertThat(get("")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"libraries":[{"id":%d,"vendor":"KYOBO","lendingModel":"OWNED","orderNo":1},
				  {"id":%d,"vendor":"KYOBO","lendingModel":"OWNED","orderNo":2},
				  {"id":%d,"vendor":"PORTAL","lendingModel":null,"orderNo":1},{"id":%d}]}
				""".formatted(open, seoulAll, busanResidence, unverified));
	}

	/** Cycle 08 (AC-20): the phone note's flag comes with the list. */
	@Test
	void mobileSiteDiffersIsListed() {
		jdbc.sql("UPDATE library.library SET mobile_site_differs = true WHERE id = ?").param(seoulAll).update();
		assertThat(get("")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"libraries":[{"id":%d,"mobileSiteDiffers":false},{"id":%d,"mobileSiteDiffers":true},
				  {"id":%d,"mobileSiteDiffers":false},{"id":%d,"mobileSiteDiffers":false}]}
				""".formatted(open, seoulAll, busanResidence, unverified));
	}

	@Test
	void workInJungSeoulMatchesSeoulLibrariesByWork() { // AC-4 server side
		assertThat(get("?residenceRegionIds=" + HAEUNDAE + "&workRegionIds=" + JUNG_SEOUL)).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"libraries":[
					  {"id":%d,"eligibility":"OPEN","matchedBy":[]},
					  {"id":%d,"eligibility":"ELIGIBLE","matchedBy":["WORK"]},
					  {"id":%d,"eligibility":"ELIGIBLE","matchedBy":["RESIDENCE"]},
					  {"id":%d,"eligibility":"UNVERIFIED","matchedBy":[]}
					]}
					""".formatted(open, seoulAll, busanResidence, unverified));
	}

	@Test
	void bothRegionsMatchingGiveBoth() {
		assertThat(get("?residenceRegionIds=" + MAPO + "&workRegionIds=" + JUNG_SEOUL)).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"libraries":[
					  {"id":%d,"matchedBy":[]},
					  {"id":%d,"eligibility":"ELIGIBLE","matchedBy":["RESIDENCE","WORK"]},
					  {"id":%d,"eligibility":"UNKNOWN","matchedBy":[]},
					  {"id":%d,"matchedBy":[]}
					]}
					""".formatted(open, seoulAll, busanResidence, unverified));
	}

	/** "지역 외는 방문 가입" (user 2026-10-20): visitRequired follows the regions sent, the flag comes as stored. */
	@Test
	void visitOutsideRegionGivesTheVisitOfTheRegionsSent() {
		rule(seoulAll, "RESIDENCE", "NATIONWIDE", null);
		jdbc.sql("UPDATE library.library SET visit_outside_region = true WHERE id = ?").param(seoulAll).update();

		assertThat(get("")).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"libraries":[{"id":%d},{"id":%d,"eligibility":"OPEN","visitRequired":true,"visitOutsideRegion":true},
				  {"id":%d},{"id":%d}]}
				""".formatted(open, seoulAll, busanResidence, unverified));
		assertThat(get("?residenceRegionIds=" + MAPO)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"libraries":[{"id":%d},
				  {"id":%d,"eligibility":"ELIGIBLE","visitRequired":false,"visitOutsideRegion":true},
				  {"id":%d},{"id":%d}]}
				""".formatted(open, seoulAll, busanResidence, unverified));
		assertThat(get("?residenceRegionIds=" + HAEUNDAE)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"libraries":[{"id":%d},{"id":%d,"eligibility":"OPEN","visitRequired":true,"visitOutsideRegion":true},
				  {"id":%d},{"id":%d}]}
				""".formatted(open, seoulAll, busanResidence, unverified));
	}

	@Test
	void abolishedRegionIs422() {
		assertThat(get("?workRegionIds=" + TEST_ABOLISHED)).hasStatus(HttpStatus.UNPROCESSABLE_CONTENT)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"invalid_region","errors":[{"field":"workRegionIds","code":"abolished","value":9001}]}
					""");
	}

	@Test
	void badFormatAndTooManyAre400() {
		assertThat(get("?residenceRegionIds=1114,x")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"validation_error","errors":[{"field":"residenceRegionIds","code":"invalid_format"}]}
					""");
		String many = String.join(",", java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(String::valueOf).toList());
		assertThat(get("?workRegionIds=" + many)).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"code":"validation_error","errors":[{"field":"workRegionIds","code":"too_many"}]}
					""");
	}

	private MvcTestResult get(String query) {
		return mvc.get().uri("/api/v1/libraries" + query).exchange();
	}

	private long library(String name, String summary, boolean visitRequired) {
		return jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, base_url, join_method,
				  eligibility_summary, visit_required)
				VALUES (?, 'KYOBO_V1', 'KYOBO', 'OWNED', ?, 'test', ?, ?) RETURNING id
				""")
			.params("테스트 " + name, "https://" + Integer.toHexString(name.hashCode()) + ".list.test", summary,
					visitRequired)
			.query(Long.class)
			.single();
	}

	private void rule(long libraryId, String basis, String scopeType, Long regionId) {
		jdbc.sql("""
				INSERT INTO library.eligibility_rule (library_id, basis, scope_type, scope_region_id)
				VALUES (?, ?, ?, ?)
				""").params(libraryId, basis, scopeType, regionId).update();
	}

}
