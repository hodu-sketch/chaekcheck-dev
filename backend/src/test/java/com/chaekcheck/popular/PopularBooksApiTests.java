package com.chaekcheck.popular;

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
import com.chaekcheck.popular.internal.PopularCategory;
import com.chaekcheck.popular.internal.cache.PopularCache;

/**
 * {@code GET /api/v1/popular-books} and its covers (plan cycle 10 step 5; AC-4, AC-6, AC-7, AC-17). The month is
 * written in the test transaction and the cache read again on the test thread, so nothing stays in db-test.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PopularBooksApiTests {

	static final String URL = "/api/v1/popular-books";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	PopularCache cache;

	@Autowired
	LibraryCatalog libraryCatalog;

	PopularTestData data;

	/** In the plan without regions, scope all. */
	long planLibrary;

	/** In the plan with scope all, not with scope joinable (no regions). */
	long notJoinable;

	@BeforeEach
	void libraries() {
		data = new PopularTestData(jdbc);
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
			.isStrictlyEqualTo("{\"month\":null,\"fetchedAt\":null,\"categories\":[]}");
	}

	@Test
	void fifteenListsInDisplayOrderWithFiftyBooks() { // AC-4
		data.month();
		cache.refresh();

		var json = assertThat(mvc.get().uri(URL)).hasStatusOk().bodyJson();
		json.extractingPath("$.month").isEqualTo("2026-09");
		json.extractingPath("$.fetchedAt").isEqualTo("2026-10-03T05:01:32+09:00");
		json.extractingPath("$.categories[*].code")
			.asArray()
			.containsExactlyElementsOf(List.of(PopularCategory.values()).stream().map(PopularCategory::code).toList());
		json.extractingPath("$.categories[*].name")
			.asArray()
			.containsExactly("전체", "소설", "문학·인물", "사회", "철학·종교", "경제·경영", "자기계발", "예술", "컴퓨터·IT",
					"생활·취미", "과학", "역사", "법", "참고·정보", "지리·환경");
		json.extractingPath("$.categories[14].books").asArray().hasSize(50);
		json.extractingPath("$.categories[1].books[49].rank").isEqualTo(50);
		json.extractingPath("$.categories[1].books[49].title").isEqualTo("00000000000000000303-50");
		json.extractingPath("$.categories[1].books[49].coverUrl").isNull();
		json.extractingPath("$.categories[1].books[49].bookId").isNull();
		json.extractingPath("$.categories[1].books[49].available").isEqualTo(false);
	}

	/**
	 * A month stored before 언어 and 기술·공학 were left out (user decision 2026-10-12) shows the 15 lists still in
	 * {@code PopularCategory}, in stored display order; the gaps do not matter.
	 */
	@Test
	void listsLeftOutLaterAreNotShown() {
		data.monthWithListsLeftOutLater();
		cache.refresh();

		var json = assertThat(mvc.get().uri(URL)).hasStatusOk().bodyJson();
		json.extractingPath("$.categories[*].name")
			.asArray()
			.containsExactly("전체", "소설", "문학·인물", "사회", "철학·종교", "경제·경영", "자기계발", "예술", "컴퓨터·IT",
					"생활·취미", "과학", "역사", "법", "참고·정보", "지리·환경");
		json.extractingPath("$.categories[*].code")
			.asArray()
			.doesNotContain("00000000000000000178", "00000000000000001991");
		json.extractingPath("$.categories[4].books[0].title").isEqualTo("00000000000000001621-1");
	}

	@Test
	void availableWhenAPlanLibraryHoldsOurBook() { // AC-6
		data.month();
		data.book(1, "9799980000001", "있는 책");
		data.book(2, "9799980000002", "계획 밖 책");
		data.book(3, null, "ISBN 없는 책");
		data.book(4, "9799980000004", "우리 목록에 없는 책");
		data.ourBook(920001, "9799980000001", planLibrary);
		data.ourBook(920002, "9799980000002", 999_999L);
		long cover = data.cover(1, "/files/isbn/2026/07/06/s_t1.jpg");
		cache.refresh();

		var json = assertThat(mvc.get().uri(URL)).bodyJson();
		json.extractingPath("$.categories[0].books[0].available").isEqualTo(true);
		json.extractingPath("$.categories[0].books[0].bookId").isEqualTo(920001);
		json.extractingPath("$.categories[0].books[0].coverUrl").isEqualTo("/api/v1/popular-books/covers/" + cover);
		json.extractingPath("$.categories[0].books[1].available").isEqualTo(false);
		json.extractingPath("$.categories[0].books[1].bookId").isEqualTo(920002);
		json.extractingPath("$.categories[0].books[1].coverUrl").isNull();
		json.extractingPath("$.categories[0].books[2].bookId").isNull();
		json.extractingPath("$.categories[0].books[2].available").isEqualTo(false);
		json.extractingPath("$.categories[0].books[3].bookId").isNull();
		json.extractingPath("$.categories[0].books[3].available").isEqualTo(false);
	}

	@Test
	void theUsersPlanDecides() { // AC-7
		data.month();
		data.book(5, "9799980000005", "가입 확실하지 않은 곳의 책");
		data.ourBook(920005, "9799980000005", notJoinable);
		cache.refresh();

		assertThat(mvc.get().uri(URL)).bodyJson().extractingPath("$.categories[0].books[4].available").isEqualTo(true);
		assertThat(mvc.get().uri(URL + "?scope=joinable")).bodyJson()
			.extractingPath("$.categories[0].books[4].available")
			.isEqualTo(false);
		assertThat(mvc.get().uri(URL + "?scope=joinable&libraryIds=" + notJoinable)).bodyJson()
			.extractingPath("$.categories[0].books[4].available")
			.isEqualTo(true);
	}

	@Test
	void badParametersAre400AndUnknownRegions422() {
		cache.refresh();

		assertThat(mvc.get().uri(URL + "?scope=x")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"scope\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri(URL + "?visit=x&scope=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"scope\"}]}");
		assertThat(mvc.get().uri(URL + "?libraryIds=0")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri(URL + "?libraryIds=1&libraryIds=2")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"duplicate\"}]}");
		String many = String.join(",", java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(Long::toString).toList());
		assertThat(mvc.get().uri(URL + "?residenceRegionIds=" + many)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"residenceRegionIds\",\"code\":\"too_many\"}]}");
		assertThat(mvc.get().uri(URL + "?libraryIds=0&workRegionIds=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\"}]}");
		assertThat(mvc.get().uri(URL + "?residenceRegionIds=999999999")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_region");
	}

	@Test
	void coverWithItsTypeAndLongCaching() { // AC-17
		data.month();
		long cover = data.cover(1, "/files/isbn/2026/07/06/s_t1.jpg");

		var result = mvc.get().uri(URL + "/covers/" + cover).exchange();
		assertThat(result).hasStatusOk()
			.hasContentType("image/jpeg")
			.hasHeader("Cache-Control", "public, max-age=2592000, immutable");
		assertThat(result.getResponse().getContentAsByteArray()).containsExactly(1, 2, 3, 4);

		jdbc.sql("DELETE FROM popular.cover WHERE id = :id").param("id", cover).update();
		assertThat(mvc.get().uri(URL + "/covers/" + cover)).hasStatus(404)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("cover_not_found");
		assertThat(mvc.get().uri(URL + "/covers/x")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("cover_not_found");
		assertThat(mvc.get().uri(URL + "/covers/0")).hasStatus(404);
	}

}
