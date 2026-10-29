package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.AiSearchIndex.Candidate;
import com.chaekcheck.catalog.AiSearchIndex.SearchBook;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.SearchResult;
import com.chaekcheck.catalog.BookTextsTransfer.Source;

/**
 * {@link AiSearchIndex} and {@link BookTextsTransfer#replaceSearchBooks} on {@code catalog.book_search} (cycle 26 step
 * 3; the cases of the aisearch {@code CandidateQueryTests}, F6): points first, then plan library count, then book id,
 * cut after the order; books no plan library holds and excluded books are left out; {@code %} {@code _} match only
 * themselves. Titles, authors and library ids come from {@code catalog.book}.
 */
@SpringBootTest
@Transactional
class AiSearchIndexTests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

	/** The user's plan libraries in these tests: 101 to 110. */
	private static final Set<Long> PLAN = Set.copyOf(LongStream.rangeClosed(101, 110).boxed().toList());

	@Autowired
	AiSearchIndex index;

	@Autowired
	BookTextsTransfer transfer;

	@Autowired
	JdbcClient jdbc;

	@Test
	void pointsThenPlanLibrariesThenBookId() {
		books(book(1, "잠들기 전 과학 이야기", 1), book(2, "과학의 눈", 5), book(3, "과학 산책", 2), book(4, "요리책", 9));
		store(List.of(new Line(930003, Source.INTRO, DAY, "잠들기 전에 조금씩 읽는 과학 수필")));

		assertThat(index.find(List.of("과학", "잠들기"), Set.of(), PLAN, Set.of(), 30)).containsExactly(
				new Candidate(930003, 2, 2), new Candidate(930001, 2, 1), new Candidate(930002, 1, 5));
	}

	@Test
	void booksHoldingCoreWordsComeFirstAndTheRestStill() {
		books(book(1, "다이어트 식단", 1), book(2, "기적의 항암 식단", 9), book(3, "살빼기 레시피", 1));
		store(List.of());
		List<String> words = List.of("다이어트", "살빼기", "식단", "레시피");

		assertThat(index.find(words, Set.of("다이어트", "살빼기"), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactly(930001L, 930003L, 930002L);
		assertThat(index.find(words, Set.of("다이어트", "살빼기"), PLAN, Set.of(), 2)).extracting(Candidate::bookId)
			.containsExactly(930001L, 930003L);
	}

	/**
	 * After five runs of one text the driver prepares it on the server, where PostgreSQL would take a generic plan that
	 * counts the plan libraries of every matched row (11 s in production, 2026-10-29); the query asks for a custom plan
	 * each time.
	 */
	@Test
	void repeatedSearchesKeepCustomPlans() {
		books(book(1, "과학 산책", 1), book(2, "과학의 눈", 5));
		store(List.of());

		for (int run = 0; run < 12; run++) {
			assertThat(index.find(List.of("과학", "산책"), Set.of(), PLAN, Set.of(), 30)).hasSize(2);
		}

		Map<String, Object> plans = jdbc.sql("""
				SELECT coalesce(sum(generic_plans), 0) AS generic, coalesce(sum(custom_plans), 0) AS custom
				FROM pg_prepared_statements WHERE statement LIKE '%FROM catalog.book_search s%core_score%'""")
			.query()
			.singleRow();
		assertThat(((Number) plans.get("generic")).longValue()).isZero();
		assertThat(((Number) plans.get("custom")).longValue()).isPositive();
	}

	/** catalog/004 with intarray in its own schema (catalog/003): arrays in any order, counted once each. */
	@Test
	void planLibraryCountIsTheIntersectionOfTheTwoArrays() {
		assertThat(jdbc.sql("SELECT catalog.plan_library_count('{9,1,5,3}'::integer[], '{3,9,10}'::integer[])")
			.query(Integer.class)
			.single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT catalog.plan_library_count('{}'::integer[], '{3}'::integer[])")
			.query(Integer.class)
			.single()).isZero();
		// the built-in array operators stay as they were: intarray is not on the search path
		assertThat(jdbc.sql("SELECT count(*) FROM pg_operator o JOIN pg_namespace n ON n.oid = o.oprnamespace "
				+ "WHERE n.nspname = 'public' AND o.oprname = '&&' AND o.oprleft = 'integer[]'::regtype")
			.query(Long.class)
			.single()).isZero();
	}

	/**
	 * Several words over more than {@code FUNCTION_PLAN_MAX} plan libraries count with the SubPlan, otherwise with the
	 * function; both give the same order (the books hold only libraries 101-110, so 600 plan libraries count alike).
	 */
	@Test
	void bothPlanCountsGiveTheSameOrder() {
		books(book(1, "잠들기 전 과학 이야기", 1), book(2, "과학의 눈", 5), book(3, "과학 산책", 2), book(4, "과학 잠들기", 9));
		store(List.of());
		Set<Long> large = Set.copyOf(LongStream.rangeClosed(101, 700).boxed().toList());
		List<String> words = List.of("과학", "잠들기");

		List<Candidate> function = index.find(words, Set.of(), PLAN, Set.of(), 30);
		assertThat(function).extracting(Candidate::bookId).containsExactly(930004L, 930001L, 930002L, 930003L);
		assertThat(index.find(words, Set.of(), large, Set.of(), 30)).isEqualTo(function);
		// one word over the large plan: the function again
		assertThat(index.find(List.of("과학"), Set.of(), large, Set.of(), 30))
			.isEqualTo(index.find(List.of("과학"), Set.of(), PLAN, Set.of(), 30));
	}

	@Test
	void tiesAreCutAfterTheOrderNotByBookIdFirst() {
		List<String> rows = new ArrayList<>();
		for (int id = 1; id <= 40; id++) {
			rows.add(book(id, "소설 " + id, id == 40 ? 7 : 1));
		}
		books(rows.toArray(String[]::new));
		store(List.of());

		List<Candidate> found = index.find(List.of("소설"), Set.of(), PLAN, Set.of(), 30);

		assertThat(found).hasSize(30);
		assertThat(found.getFirst()).isEqualTo(new Candidate(930040, 1, 7));
		assertThat(found.get(1).bookId()).isEqualTo(930001);
	}

	@Test
	void booksNoPlanLibraryHoldsAndExcludedBooksAreLeftOut() {
		books("(930001, '과학', 'x', 'x', 'EBOOK', '{999}')", book(2, "과학", 1), book(3, "과학", 3));
		store(List.of());

		assertThat(index.find(List.of("과학"), Set.of(), PLAN, Set.of(930003L), 30)).extracting(Candidate::bookId)
			.containsExactly(930002L);
		assertThat(index.find(List.of("과학"), Set.of(), Set.of(), Set.of(), 30)).isEmpty();
		assertThat(index.find(List.of(), Set.of(), PLAN, Set.of(), 30)).isEmpty();
	}

	@Test
	void planCountsAndLinesOfGivenBooks() {
		books("(930001, '가', 'x', 'x', 'EBOOK', '{999}')", book(2, "나", 1), book(3, "다", 4));
		store(List.of(new Line(930002, Source.BIBLIOGRAPHIC, DAY, "한 줄이에요.")));

		assertThat(index.planCounts(List.of(930001L, 930002L, 930003L, 930004L), PLAN))
			.isEqualTo(Map.of(930002L, 1, 930003L, 4));
		assertThat(index.planCounts(List.of(930002L), Set.of())).isEmpty();
		assertThat(index.lines(List.of(930001L, 930002L))).isEqualTo(Map.of(930002L, "한 줄이에요."));
	}

	@Test
	void wildcardsBackslashesAndQuotesMatchOnlyThemselves() {
		books(book(1, "100% 실화", 1), book(2, "100점 실화", 1), book(3, "a_b", 1), book(4, "axb", 1), book(5, "a\\b", 1),
				book(6, "ab", 1), book(7, "it''s", 1));
		store(List.of());

		assertThat(index.find(List.of("100%"), Set.of(), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactly(930001L);
		assertThat(index.find(List.of("a_b"), Set.of(), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactly(930003L);
		assertThat(index.find(List.of("a\\b"), Set.of(), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactly(930005L);
		assertThat(index.find(List.of("it's"), Set.of(), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactly(930007L);
	}

	@Test
	void theSearchTextAndLibrariesComeFromTheCatalogAndBooksReadIt() {
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, content_type, library_ids, author_display)
				VALUES (930001, '코스모스', 'x', 'x', 'EBOOK', '{101,2000}', '칼 세이건')""").update();

		SearchResult result = transfer.replaceSearchBooks(List.of(930001L, 939999L),
				List.of(new Line(930001, Source.INTRO, DAY, "우주 이야기예요."), new Line(939999, Source.INTRO, DAY, "없음")));

		assertThat(result).isEqualTo(new SearchResult(1, 1, 1, 1));
		assertThat(jdbc.sql("SELECT search_text || '|' || library_ids::text FROM catalog.book_search")
			.query(String.class)
			.single()).isEqualTo("코스모스 칼세이건 우주이야기예요.|{101,2000}");
		assertThat(index.books()).containsExactly(new SearchBook(930001, "코스모스", "칼 세이건"));
		assertThat(index.version()).startsWith("1@");
	}

	@Test
	void aFileWithoutLinesCannotRemoveTheStoredOnes() {
		books(book(1, "가나", 1));
		store(List.of(new Line(930001, Source.INTRO, DAY, "한 줄")));

		assertThatThrownBy(() -> store(List.of())).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> transfer.replaceSearchBooks(List.of(930001L, 930001L), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> transfer.replaceSearchBooks(List.of(), List.of()))
			.isInstanceOf(IllegalArgumentException.class);
		assertThat(index.lines(List.of(930001L))).hasSize(1);
	}

	/** Stores every 93xxxx test book as a search book. */
	private void store(List<Line> lines) {
		List<Long> ids = jdbc.sql("SELECT id FROM catalog.book WHERE id BETWEEN 930000 AND 939999 ORDER BY id")
			.query(Long.class)
			.list();
		transfer.replaceSearchBooks(ids, lines);
	}

	private void books(String... rows) {
		jdbc.sql("INSERT INTO catalog.book (id, title, title_norm, main_title_norm, content_type, library_ids) VALUES "
				+ String.join(", ", rows)).update();
	}

	/** Book 930000 + {@code n}, held by {@code planCount} of the plan libraries and one library outside the plan. */
	private static String book(int n, String title, int planCount) {
		List<String> libraries = new ArrayList<>();
		LongStream.rangeClosed(101, 100 + planCount).forEach(id -> libraries.add(String.valueOf(id)));
		libraries.add("999");
		return "(" + (930000 + n) + ", '" + title + "', 'x', 'x', 'EBOOK', '{" + String.join(",", libraries) + "}')";
	}

}
