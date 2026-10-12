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

/**
 * {@code GET /api/v1/picks} and its covers (plan cycle 14 step 5; AC-4, AC-4b, AC-6, AC-7, AC-14b). The picks are
 * written in the test transaction and the cache read again on the test thread, so nothing stays in db-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PicksApiTests {

	static final String URL = "/api/v1/picks";

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
			.isStrictlyEqualTo("{\"fetchedAt\":null,\"categories\":[]}");
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
				{"fetchedAt":"2026-10-12T05:00:41+09:00","categories":[
				 {"code":"ALL","name":"전체","books":[
				  {"recomNo":"20260928084907621100","category":"425","recommendedMonth":"2026-10","title":"나","coverUrl":null,"bookId":null,"available":false},
				  {"recomNo":"20260922163135829100","category":"11","recommendedMonth":"2026-10","title":"가","coverUrl":null,"bookId":null,"available":false},
				  {"recomNo":"20260801000000000001","category":"1000","recommendedMonth":"2026-08","title":"라","coverUrl":null,"bookId":null,"available":false},
				  {"recomNo":"20260801000000000000","category":"999","recommendedMonth":"2026-08","title":"다","coverUrl":null,"bookId":null,"available":false}]},
				 {"code":"11","name":"문학","books":[
				  {"recomNo":"20260922163135829100","category":"11","recommendedMonth":"2026-10","title":"가","coverUrl":null,"bookId":null,"available":false}]},
				 {"code":"425","name":"테마","books":[
				  {"recomNo":"20260928084907621100","category":"425","recommendedMonth":"2026-10","title":"나","coverUrl":null,"bookId":null,"available":false}]},
				 {"code":"999","name":"새분야","books":[
				  {"recomNo":"20260801000000000000","category":"999","recommendedMonth":"2026-08","title":"다","coverUrl":null,"bookId":null,"available":false}]},
				 {"code":"1000","name":"기타","books":[
				  {"recomNo":"20260801000000000001","category":"1000","recommendedMonth":"2026-08","title":"라","coverUrl":null,"bookId":null,"available":false}]}]}
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

		assertThat(mvc.get().uri(URL)).bodyJson()
			.extractingPath("$.categories[0].books[0].coverUrl")
			.isEqualTo("/api/v1/picks/covers/" + cover);
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
	void theOldAddressIsGone() {
		assertThat(mvc.get().uri("/api/v1/popular-books")).hasStatus(404);
	}

}
