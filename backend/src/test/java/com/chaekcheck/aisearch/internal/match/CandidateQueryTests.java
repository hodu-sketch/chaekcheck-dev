package com.chaekcheck.aisearch.internal.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.aisearch.TestSearchBooks;
import com.chaekcheck.aisearch.TestSearchBooks.Book;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.aisearch.internal.match.CandidateQuery.Candidate;

/**
 * {@link CandidateQuery} against db-test (cycle 24 handoff step 5; plan library counts in SQL since 2026-10-25):
 * points first, then plan library count, then book id, cut after the order; books no plan library holds and excluded
 * books are left out; keywords are tidied, short ones dropped, at most eight, and {@code %} {@code _} match only
 * themselves.
 */
@SpringBootTest
@Transactional
class CandidateQueryTests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

	/** The user's plan libraries in these tests: 101 to 110. */
	private static final Set<Long> PLAN = Set.copyOf(LongStream.rangeClosed(101, 110).boxed().toList());

	@Autowired
	CandidateQuery query;

	@Autowired
	TestSearchBooks texts;

	@Test
	void pointsThenPlanLibrariesThenBookId() {
		store(List.of(book(1, "잠들기 전 과학 이야기", 1), book(2, "과학의 눈", 5), book(3, "과학 산책", 2), book(4, "요리책", 9)),
				List.of(new Line(3, Source.INTRO, DAY, "잠들기 전에 조금씩 읽는 과학 수필")));

		List<Candidate> found = query.find(List.of("과학", "잠들기"), PLAN, Set.of(), 30);

		assertThat(found).containsExactly(new Candidate(3, 2, 2), new Candidate(1, 2, 1), new Candidate(2, 1, 5));
	}

	@Test
	void booksHoldingCoreWordsComeFirstAndTheRestStill() { // cycle 24 1단계
		store(List.of(book(1, "다이어트 식단", 1), book(2, "기적의 항암 식단", 9), book(3, "살빼기 레시피", 1)), List.of());

		assertThat(query.find(List.of("식단", "레시피"), List.of("다이어트", "살빼기"), PLAN, Set.of(), 30))
			.extracting(Candidate::bookId)
			.containsExactly(1L, 3L, 2L);
		assertThat(query.find(List.of("식단", "레시피"), List.of("다이어트", "살빼기"), PLAN, Set.of(), 2))
			.extracting(Candidate::bookId)
			.containsExactly(1L, 3L);
	}

	@Test
	void tiesAreCutAfterTheOrderNotByBookIdFirst() {
		List<Book> books = new ArrayList<>();
		for (int id = 1; id <= 40; id++) {
			books.add(book(id, "소설 " + id, id == 40 ? 7 : 1));
		}
		store(books, List.of());

		List<Candidate> found = query.find(List.of("소설"), PLAN, Set.of(), 30);

		assertThat(found).hasSize(30);
		assertThat(found.getFirst()).isEqualTo(new Candidate(40, 1, 7));
		assertThat(found.get(1).bookId()).isEqualTo(1);
	}

	@Test
	void booksNoPlanLibraryHoldsAndExcludedBooksAreLeftOut() {
		store(List.of(new Book(1, "과학", null, new long[] { 999 }), book(2, "과학", 1), book(3, "과학", 3)), List.of());

		assertThat(query.find(List.of("과학"), PLAN, Set.of(3L), 30)).extracting(Candidate::bookId)
			.containsExactly(2L);
		assertThat(query.find(List.of("과학"), Set.of(), Set.of(), 30)).isEmpty();
	}

	@Test
	void planCountsOfGivenBooks() {
		store(List.of(new Book(1, "가", null, new long[] { 999 }), book(2, "나", 1), book(3, "다", 4)), List.of());

		assertThat(query.planCounts(List.of(1L, 2L, 3L, 4L), PLAN)).isEqualTo(Map.of(2L, 1, 3L, 4));
		assertThat(query.planCounts(List.of(2L), Set.of())).isEmpty();
	}

	@Test
	void wildcardsBackslashesAndQuotesMatchOnlyThemselves() {
		store(List.of(book(1, "100% 실화", 1), book(2, "100점 실화", 1), book(3, "a_b", 1), book(4, "axb", 1)), List.of());

		assertThat(query.find(List.of("100%"), PLAN, Set.of(), 30)).extracting(Candidate::bookId).containsExactly(1L);
		assertThat(query.find(List.of("a_b"), PLAN, Set.of(), 30)).extracting(Candidate::bookId).containsExactly(3L);

		store(List.of(book(1, "a\\b", 1), book(2, "ab", 1), book(3, "it's", 1)), List.of());
		assertThat(query.find(List.of("a\\b"), PLAN, Set.of(), 30)).extracting(Candidate::bookId).containsExactly(1L);
		assertThat(query.find(List.of("it's"), PLAN, Set.of(), 30)).extracting(Candidate::bookId).containsExactly(3L);
	}

	@Test
	void keywordsAreTidiedShortOnesDroppedAndAtMostEight() {
		assertThat(CandidateQuery.usableKeywords(List.of("한국 SF", "ＳＦ", "책", " ", "sf", "과학 소설")))
			.containsExactly("한국sf", "sf", "과학소설");
		assertThat(CandidateQuery.usableKeywords(
				List.of("가가", "나나", "다다", "라라", "마마", "바바", "사사", "아아", "자자", "차차")))
			.hasSize(8)
			.endsWith("아아");
		assertThat(CandidateQuery.usableKeywords(List.of("가", "나"))).isEmpty();
	}

	private void store(List<Book> books, List<Line> lines) {
		texts.replace(books, lines);
	}

	/** A book held by {@code planCount} of the plan libraries and one library outside the plan. */
	private static Book book(long id, String title, int planCount) {
		long[] libraries = LongStream.concat(LongStream.rangeClosed(101, 100 + planCount), LongStream.of(999)).toArray();
		return new Book(id, title, null, libraries);
	}

}
