package com.chaekcheck.foryou.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.foryou.internal.ForYouSnapshot;
import com.chaekcheck.foryou.internal.listed.ListedSource;
import com.chaekcheck.foryou.internal.listed.ListedSource.ListedRef;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.BookTagTransfer.Tag;

/**
 * {@code GET /api/v1/for-you/books} and {@code /similar-books} (cycle 23 step 6; AC-3, AC-5, AC-6 server part). Build
 * rows are written in the test transaction and the memory copy read again on the test thread.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ForYouControllerTests {

	static final String BOOKS = "/api/v1/for-you/books";

	static final String SIMILAR = "/api/v1/for-you/similar-books";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ForYouSnapshot snapshot;

	@Autowired
	LibraryCatalog libraryCatalog;

	@MockitoBean
	ListedSource listedSource;

	final List<ListedRef> listed = new ArrayList<>();

	/** In the plan with scope all and with scope joinable. */
	long joinable;

	/** In the plan with scope all only. */
	long notJoinable;

	@BeforeEach
	void setUp() {
		jdbc.sql("DELETE FROM foryou.build_run").update();
		when(listedSource.listed()).thenAnswer(invocation -> List.copyOf(listed));
		List<SearchTarget> all = libraryCatalog.planSearch(Set.of(), Set.of());
		Set<Long> joinableIds = Set.copyOf(libraryCatalog
			.planSearch(Set.of(), Set.of(), new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.ANY))
			.stream()
			.map(SearchTarget::id)
			.toList());
		joinable = joinableIds.iterator().next();
		notJoinable = all.stream().map(SearchTarget::id).filter(id -> !joinableIds.contains(id)).findFirst().orElseThrow();
		book(940001, "첫 책", "{" + joinable + "," + notJoinable + "}", "가");
		book(940002, "둘째 책", "{" + notJoinable + "}", "나");
		book(940003, "셋째 책", "{" + joinable + "}", "다");
		book(940004, "넷째 책", "{" + joinable + "," + notJoinable + "}", "가");
		book(940005, "어디에도 없는 책", "{}", "라");
		jdbc.sql("INSERT INTO foryou.book_blurb VALUES (940001, '잔잔한 이야기예요.', DATE '2026-10-24')").update();
		jdbc.sql("INSERT INTO foryou.book_cover VALUES (940001, '0123456789abcdef0123456789abcdef01234567')").update();
	}

	@Test
	void beforeAnyBuildTheListedBooksComeByHeldLibraries() {
		listed.add(new ListedRef(940003, List.of("kaist"), new long[] { joinable }, List.of()));
		listed.add(new ListedRef(940001, List.of("snu100", "kaist"), new long[] { joinable, notJoinable },
				List.of(new Tag("m01", "mood", "잔잔한")), "목록의 AI 요약", null, 2025));
		snapshot.refresh();

		assertThat(mvc.get().uri(BOOKS)).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"builtOn": null, "books": [
				  {"bookId": 940001, "title": "첫 책", "author": "가 지음", "publisher": "출판사",
				   "coverUrl": "/covers/0123456789abcdef0123456789abcdef01234567.jpg", "blurb": "잔잔한 이야기예요.",
				   "tags": [{"code": "m01", "axis": "mood", "name": "잔잔한"}], "planLibraryCount": 2,
				   "lists": ["snu100", "kaist"], "aiSummary": "목록의 AI 요약", "recommendedMonth": null, "yearBook": 2025},
				  {"bookId": 940003, "coverUrl": null, "blurb": null, "tags": [], "planLibraryCount": 1, "lists": ["kaist"],
				   "aiSummary": null, "yearBook": null}
				]}""");
	}

	@Test
	void theBaseListInItsOrderOnlyWithPlanLibraries() { // AC-5
		build(new long[] { 940002, 940004, 940001, 940005 }, new double[] { 0, 0, 0, 0 });

		assertThat(mvc.get().uri(BOOKS)).hasStatusOk().bodyJson().extractingPath("$.books[*].bookId")
			.isEqualTo(List.of(940002, 940004, 940001));
		assertThat(mvc.get().uri(BOOKS + "?scope=joinable")).hasStatusOk().bodyJson()
			.extractingPath("$.books[*].planLibraryCount")
			.isEqualTo(List.of(1, 1));
		assertThat(mvc.get().uri(BOOKS)).bodyJson().extractingPath("$.builtOn").isEqualTo("2026-10-26");
	}

	@Test
	void noScoreOrSourceIsSent() throws Exception {
		build(new long[] { 940001 }, new double[] { 88 });

		String body = mvc.get().uri(BOOKS).exchange().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
		assertThat(body).doesNotContain("score", "source", "kyobo", "88");
	}

	@Test
	void similarBooksScoredFirstThenPlanLibrariesThenId() { // AC-6 server part
		jdbc.sql("INSERT INTO foryou.book_neighbour VALUES (940001, ARRAY[940003, 940002, 940004, 940005, 999999],"
				+ " DATE '2026-10-24'), (940003, ARRAY[940001, 940004], DATE '2026-10-24')").update();
		build(new long[] { 940002 }, new double[] { 5 });

		assertThat(mvc.get().uri(SIMILAR + "?baseBookIds=940001,940003,940009&reshowBookIds=940004,940002,940001"))
			.hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("""
					{"baseBooks": [{"bookId": 940001, "title": "첫 책"}, {"bookId": 940003, "title": "셋째 책"}],
					 "books": [{"bookId": 940002}, {"bookId": 940004, "authorKey": "가"}],
					 "reshowBooks": [{"bookId": 940004}, {"bookId": 940002}]}""");
		// the base books are never in books; 940005 is in no plan library; 999999 is no book
		assertThat(mvc.get().uri(SIMILAR + "?baseBookIds=940001&scope=joinable")).bodyJson()
			.extractingPath("$.books[*].bookId")
			.isEqualTo(List.of(940003, 940004));
	}

	@Test
	void aNeighbourThatIsAnotherEditionCountsAsTheScoredEdition() {
		// 940006 is another edition of 「넷째 책」 (same main title and author), held by fewer libraries
		book(940006, "넷째 책", "{" + notJoinable + "}", "가");
		jdbc.sql("INSERT INTO foryou.book_neighbour VALUES (940003, ARRAY[940006, 940002], DATE '2026-10-24')").update();
		build(new long[] { 940004 }, new double[] { 9 });

		assertThat(mvc.get().uri(SIMILAR + "?baseBookIds=940003")).hasStatusOk()
			.bodyJson()
			.extractingPath("$.books[*].bookId")
			.isEqualTo(List.of(940004, 940002));
		// a base book's other edition is not a pick either
		jdbc.sql("INSERT INTO foryou.book_neighbour VALUES (940004, ARRAY[940006, 940003], DATE '2026-10-24')").update();
		assertThat(mvc.get().uri(SIMILAR + "?baseBookIds=940004")).bodyJson()
			.extractingPath("$.books[*].bookId")
			.isEqualTo(List.of(940003));
	}

	@Test
	void validationCodes() {
		invalid(SIMILAR, "baseBookIds", "required");
		invalid(SIMILAR + "?baseBookIds=1,2,3,4", "baseBookIds", "too_many");
		invalid(SIMILAR + "?baseBookIds=1,1", "baseBookIds", "duplicate");
		invalid(SIMILAR + "?baseBookIds=1&baseBookIds=2", "baseBookIds", "duplicate");
		invalid(SIMILAR + "?baseBookIds=a", "baseBookIds", "invalid_format");
		invalid(SIMILAR + "?baseBookIds=0", "baseBookIds", "invalid_format");
		invalid(SIMILAR + "?baseBookIds=1&reshowBookIds=1,2,3,4,5,6,7", "reshowBookIds", "too_many");
		invalid(SIMILAR + "?baseBookIds=1&reshowBookIds=x", "reshowBookIds", "invalid_format");
		invalid(BOOKS + "?scope=mine", "scope", "invalid_format");
		assertThat(mvc.get().uri(BOOKS + "?residenceRegionIds=999999")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_region");
		assertThat(mvc.get().uri(SIMILAR + "?baseBookIds=1,2,3&reshowBookIds=1,2,3,4,5,6")).hasStatusOk();
	}

	private void invalid(String uri, String field, String code) {
		assertThat(mvc.get().uri(uri)).hasStatus(400).bodyJson().isLenientlyEqualTo("""
				{"code": "validation_error", "errors": [{"field": "%s", "code": "%s"}]}""".formatted(field, code));
	}

	private void book(long id, String title, String libraries, String author) {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, author_display, publisher, title_norm, main_title_norm, content_type,
				  library_ids)
				VALUES (?, ?, ?, '출판사', 'x', ?, 'EBOOK', CAST(? AS bigint[]))""")
			.params(id, title, author + " 지음", title, libraries)
			.update();
		jdbc.sql("INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)"
				+ " VALUES (?, ?, 'x', ?, 'AUTHOR', 0)").params(id, author, author).update();
	}

	private void build(long[] entries, double[] scores) {
		long run = jdbc.sql("INSERT INTO foryou.build_run (built_on, started_at, status) VALUES (DATE '2026-10-26', now(),"
				+ " 'succeeded') RETURNING id").query(Long.class).single();
		for (int i = 0; i < entries.length; i++) {
			jdbc.sql("INSERT INTO foryou.base_entry VALUES (?, ?, ?, true)").params(run, i + 1, entries[i]).update();
			if (scores[i] > 0) {
				jdbc.sql("INSERT INTO foryou.book_score VALUES (?, ?, ?)").params(run, entries[i], scores[i]).update();
			}
		}
		snapshot.refresh();
	}

}
