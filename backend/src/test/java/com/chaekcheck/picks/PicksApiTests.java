package com.chaekcheck.picks;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.internal.cache.PicksCache;
import com.chaekcheck.picks.internal.lists.CoverFiles;

/**
 * {@code GET /api/v1/picks} and its covers (plan cycle 14 step 5; AC-4, AC-4b, AC-6, AC-7, AC-14b). The picks are
 * written in the test transaction and the cache read again on the test thread, so nothing stays in db-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PicksApiTests {

	/** The 책첵 summary of 「히스토리아 비테이」 put in catalog.book_summary here (cycle 26: the list shows it as it is). */
	private static final String HISTORIA_SUMMARY = "팬데믹, 기후 변화, AI 같은 위기 앞에서 지구 생명의 역사를 돌아보며 해법을 찾는 생태학자 최재천의 책이에요.";


	static final String URL = "/api/v1/picks/librarian";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PicksCache cache;

	@Autowired
	LibraryCatalog libraryCatalog;

	PicksTestData data;

	/** In the plan without regions, scope all. */
	long planLibrary;

	/** In the plan with scope all, not with scope joinable (no regions). */
	long notJoinable;

	@BeforeEach
	void libraries() {
		data = new PicksTestData(jdbc);
		List<SearchTarget> all = libraryCatalog.planSearch(Set.of(), Set.of());
		Set<Long> joinable = Set.copyOf(libraryCatalog
			.planSearch(Set.of(), Set.of(), new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.ANY))
			.stream()
			.map(SearchTarget::id)
			.toList());
		planLibrary = all.getFirst().id();
		notJoinable = all.stream().map(SearchTarget::id).filter(id -> !joinable.contains(id)).findFirst().orElseThrow();
	}

	@Test
	void nothingStoredIsAnEmptyAnswer() {
		cache.refresh();

		assertThat(mvc.get().uri(URL)).hasStatusOk()
			.bodyJson()
			.isStrictlyEqualTo("{\"list\":\"librarian\",\"fetchedAt\":null,\"categories\":[]}");
	}

	@Test
	void fieldsInOrderWithoutEmptyOnesAndNewCodesLast() { // AC-4, AC-4b
		data.run();
		data.pick("20260922163135829100", "11", "어문학", "2026-10", "2026-09-22", "가", null, null);
		data.pick("20260928084907621100", "425", null, "2026-10", "2026-09-28", "나", null, null);
		data.pick("20260801000000000000", "999", "새분야", "2026-08", null, "다", null, null);
		data.pick("20260801000000000001", "1000", null, "2026-08", null, "라", null, null);
		cache.refresh();

		assertThat(mvc.get().uri(URL)).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"list":"librarian","fetchedAt":"2026-10-12T05:00:41+09:00","categories":[
				 {"code":"ALL","name":"전체","books":[
				  {"id":"20260928084907621100","category":"425","title":"나","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-10","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":"other"},
				  {"id":"20260922163135829100","category":"11","title":"가","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-10","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":"literature"},
				  {"id":"20260801000000000001","category":"1000","title":"라","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-08","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":null},
				  {"id":"20260801000000000000","category":"999","title":"다","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-08","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":null}]},
				 {"code":"11","name":"문학","books":[
				  {"id":"20260922163135829100","category":"11","title":"가","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-10","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":"literature"}]},
				 {"code":"425","name":"테마","books":[
				  {"id":"20260928084907621100","category":"425","title":"나","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-10","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":"other"}]},
				 {"code":"999","name":"새분야","books":[
				  {"id":"20260801000000000000","category":"999","title":"다","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-08","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":null}]},
				 {"code":"1000","name":"기타","books":[
				  {"id":"20260801000000000001","category":"1000","title":"라","listTitle":null,"coverUrl":null,"bookId":null,"available":false,"recommendedMonth":"2026-08","yearBook":null,"videoId":null,"tocNo":null,"aiSummary":null,"fieldGroup":null}]}]}
				""");
	}

	@Test
	void availableFollowsThePlan() { // AC-6, AC-7
		data.run();
		// by ISBN, held by a plan library
		data.pick("4", "11", null, "2026-10", null, "가", null, "9799990000011");
		data.ourBook(910001, "9799990000011", "가", null, planLibrary);
		// by title and first author, held by a library outside the joinable plan
		data.pick("3", "11", null, "2026-10", null, "최소한의 양자역학 : 부제", "프랑크 베르스트라테 지음 ;최진영 옮김", null);
		data.ourBook(910002, null, "최소한의 양자역학", "프랑크 베르스트라테", notJoinable);
		// ours, held by no library
		data.pick("2", "11", null, "2026-10", null, "다", null, "9799990000028");
		data.ourBook(910003, "9799990000028", "다", null);
		// not ours
		data.pick("1", "11", null, "2026-10", null, "라", null, "9799990000035");
		cache.refresh();

		assertThat(mvc.get().uri(URL)).hasStatusOk()
			.bodyJson()
			.extractingPath("$.categories[0].books[*].available")
			.isEqualTo(List.of(true, true, false, false));
		assertThat(mvc.get().uri(URL)).bodyJson()
			.extractingPath("$.categories[0].books[*].bookId")
			.isEqualTo(java.util.Arrays.asList(910001, 910002, 910003, null));
		assertThat(mvc.get().uri(URL + "?scope=joinable")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.categories[0].books[*].available")
			.isEqualTo(List.of(true, false, false, false));
	}

	@Test
	void badPlanParametersAre400And422() {
		assertThat(mvc.get().uri(URL + "?scope=nearby")).hasStatus(400)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("validation_error");
		assertThat(mvc.get().uri(URL + "?residenceRegionIds=999999")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_region");
	}

	@Test
	void coversAreServedAndMissingOnesAre404() { // AC-14b
		data.run();
		data.pick("1", "11", null, "2026-10", null, "가", null, null);
		long cover = data.cover("1", "https://www.nl.go.kr/afile/fileDownload/a");
		cache.refresh();

		// cycle 26 (ADR-0163): the list names the picture on CloudFront; the old address still answers until deploy 2
		assertThat(mvc.get().uri(URL)).bodyJson()
			.extractingPath("$.categories[0].books[0].coverUrl")
			.isEqualTo("https://d1bdzw8sfjph5d.cloudfront.net/covers/"
					+ CoverFiles.key("https://www.nl.go.kr/afile/fileDownload/a") + ".jpg");
		assertThat(mvc.get().uri("/api/v1/picks/covers/" + cover)).hasStatusOk()
			.hasContentType("image/jpeg")
			.hasHeader("Cache-Control", "public, max-age=2592000, immutable");
		assertThat(mvc.get().uri("/api/v1/picks/covers/" + (cover + 1000))).hasStatus(404)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("cover_not_found");
		assertThat(mvc.get().uri("/api/v1/picks/covers/abc")).hasStatus(404);
		jdbc.sql("DELETE FROM picks.cover").update();
		assertThat(mvc.get().uri("/api/v1/picks/covers/" + cover)).hasStatus(404);
	}

	@Test
	void theOldAddressesAreGone() {
		assertThat(mvc.get().uri("/api/v1/popular-books")).hasStatus(404);
		assertThat(mvc.get().uri("/api/v1/picks")).hasStatus(404); // cycle 21 (ADR-0126)
	}

	@Test
	void anUnknownListIs404() { // cycle 21 (ADR-0126)
		assertThat(mvc.get().uri("/api/v1/picks/yes24")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("list_not_found");
		assertThat(mvc.get().uri("/api/v1/picks/LIBRARIAN")).hasStatus(404);
	}

	@Test
	void leedongjinAndSnuListsCarryTheirOwnColumns() { // cycle 21 AC-4, AC-5, AC-8, AC-15, AC-16
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position)
				VALUES ('leedongjin', 'science', '과학·의학', 1), ('snu100', 'korean_lit', '한국문학', 1),
				  ('snu100', 'foreign_lit', '외국문학', 2)""").update();
		data.ourBook(910011, null, "히스토리아 비테이", null, planLibrary);
		jdbc.sql("INSERT INTO catalog.book_summary VALUES (910011, ?, DATE '2026-10-28')").param(HISTORIA_SUMMARY).update();
		data.ourBook(910012, null, "햄릿(Hamlet)", null, notJoinable);
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, year_book,
				  video_id, book_id)
				VALUES ('leedongjin', 'science', 1, '히스토리아 비테이', DATE '2026-06-01', NULL, 'lMx0zEd1qtY', 910011)
				""").update();
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, toc_no, book_id)
				VALUES ('snu100', 'foreign_lit', 23, '셰익스피어: 햄릿 외 3편', 23, 910012),
				  ('snu100', 'korean_lit', 17, '광장', 17, NULL)""").update();
		cache.refresh();

		assertThat(mvc.get().uri("/api/v1/picks/leedongjin")).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"list":"leedongjin","fetchedAt":null,"categories":[
				 {"code":"ALL","name":"전체","books":[%1$s]},
				 {"code":"science","name":"과학·의학","books":[%1$s]}]}
				""".formatted("""
				{"id":"%d","category":"science","title":"히스토리아 비테이","listTitle":null,"coverUrl":null,
				 "bookId":910011,"available":true,"recommendedMonth":"2026-06","yearBook":null,"videoId":"lMx0zEd1qtY",
				 "tocNo":null,"aiSummary":"%s","fieldGroup":"science"}""".formatted(rowId("leedongjin", "히스토리아 비테이"), HISTORIA_SUMMARY)));
		var snu = assertThat(mvc.get().uri("/api/v1/picks/snu100")).hasStatusOk().bodyJson();
		snu.extractingPath("$.categories[*].code").isEqualTo(List.of("ALL", "korean_lit", "foreign_lit"));
		snu.extractingPath("$.categories[0].books[*].tocNo").isEqualTo(List.of(17, 23));
		// AC-15: no book of ours, the list's title; AC-16: our title without edition marks, the list's one beside
		snu.extractingPath("$.categories[0].books[*].title").isEqualTo(List.of("광장", "햄릿"));
		snu.extractingPath("$.categories[0].books[*].listTitle")
			.isEqualTo(java.util.Arrays.asList(null, "셰익스피어: 햄릿 외 3편"));
		snu.extractingPath("$.categories[0].books[*].bookId").isEqualTo(java.util.Arrays.asList(null, 910012));
		snu.extractingPath("$.categories[0].books[*].recommendedMonth").isEqualTo(java.util.Arrays.asList(null, null));
		// the librarian list is still empty: the lists do not mix
		assertThat(mvc.get().uri(URL)).bodyJson().extractingPath("$.categories").asArray().isEmpty();
	}

	@Test
	void kaistListIsNewestYearFirstThenPosition() { // user 2026-10-19, years 2026-10-20
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position)
				VALUES ('kaist', 'ai', 'AI', 2), ('kaist', 'nature', '자연', 1)""").update();
		data.ourBook(910021, "9791190400657", "뇌의 역습", null, planLibrary);
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, author, publisher, isbn13, book_id,
				  year_book)
				VALUES ('kaist', 'ai', 1, '에이아이 첫째', NULL, NULL, NULL, NULL, 2025),
				  ('kaist', 'nature', 2, '뇌의 역습', '볼레', '착한책가게', '9791190400657', 910021, 2026),
				  ('kaist', 'nature', 1, '지구 관찰자의 기후 노트', '이은지', '한길사', NULL, NULL, 2026)""").update();
		cache.refresh();

		var kaist = assertThat(mvc.get().uri("/api/v1/picks/kaist")).hasStatusOk().bodyJson();
		kaist.extractingPath("$.list").isEqualTo("kaist");
		kaist.extractingPath("$.categories[*].code").isEqualTo(List.of("ALL", "nature", "ai"));
		kaist.extractingPath("$.categories[0].books[*].title")
			.isEqualTo(List.of("지구 관찰자의 기후 노트", "뇌의 역습", "에이아이 첫째"));
		kaist.extractingPath("$.categories[0].books[*].bookId")
			.isEqualTo(java.util.Arrays.asList(null, 910021, null));
		kaist.extractingPath("$.categories[0].books[1].available").isEqualTo(true);
		kaist.extractingPath("$.categories[0].books[*].yearBook").isEqualTo(List.of(2026, 2026, 2025));
		kaist.extractingPath("$.categories[2].books[*].title").isEqualTo(List.of("에이아이 첫째"));
	}

	@Test
	void nobelListIsInFieldOrderThenTheListsOrder() { // user 2026-10-20
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position)
				VALUES ('nobel', 'modern', '20세기', 3), ('nobel', 'ancient', '고대·중세', 1),
				  ('nobel', 'early_modern', '근대', 2)""").update();
		data.ourBook(910031, "9791190400664", "돈키호테", null, planLibrary);
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, author, book_id)
				VALUES ('nobel', 'modern', 1, '모든 것이 산산이 무너지다', '치누아 아체베', NULL),
				  ('nobel', 'early_modern', 1, '돈키호테', '미겔 데 세르반테스', 910031),
				  ('nobel', 'ancient', 2, '일리아스', '호메로스', NULL),
				  ('nobel', 'ancient', 1, '신곡', '단테 알리기에리', NULL)""").update();
		cache.refresh();

		var nobel = assertThat(mvc.get().uri("/api/v1/picks/nobel")).hasStatusOk().bodyJson();
		nobel.extractingPath("$.list").isEqualTo("nobel");
		nobel.extractingPath("$.categories[*].code").isEqualTo(List.of("ALL", "ancient", "early_modern", "modern"));
		nobel.extractingPath("$.categories[0].books[*].title")
			.isEqualTo(List.of("신곡", "일리아스", "돈키호테", "모든 것이 산산이 무너지다"));
		nobel.extractingPath("$.categories[0].books[*].bookId")
			.isEqualTo(java.util.Arrays.asList(null, null, 910031, null));
		nobel.extractingPath("$.categories[0].books[2].available").isEqualTo(true);
		nobel.extractingPath("$.categories[3].books[*].title").isEqualTo(List.of("모든 것이 산산이 무너지다"));
	}

	private long rowId(String list, String title) {
		return jdbc.sql("SELECT id FROM picks.list_book WHERE list_key = :list AND title = :title")
			.param("list", list)
			.param("title", title)
			.query(Long.class)
			.single();
	}

}
