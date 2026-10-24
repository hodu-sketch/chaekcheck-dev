package com.chaekcheck.aisearch.internal.match;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;
import com.chaekcheck.aisearch.internal.match.CandidateQuery.Candidate;
import com.chaekcheck.aisearch.internal.match.PlanTargets.Target;
import com.chaekcheck.picks.ListedBooks.ListedBook;

/**
 * {@link CandidateQuery} against db-test (cycle 24 handoff step 5): points first, then plan library count, then book
 * id, cut after the order; books no plan library holds are left out; keywords are tidied, short ones dropped, at most
 * eight, and {@code %} {@code _} match only themselves.
 */
@SpringBootTest
@Transactional
class CandidateQueryTests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 24);

	@Autowired
	CandidateQuery query;

	@Autowired
	AiSearchBookTexts texts;

	@Test
	void pointsThenPlanLibrariesThenBookId() {
		store(List.of(new Book(1, "잠들기 전 과학 이야기", null), new Book(2, "과학의 눈", null), new Book(3, "과학 산책", null),
				new Book(4, "요리책", null)),
				List.of(new Line(3, Source.INTRO, DAY, "잠들기 전에 조금씩 읽는 과학 수필")));

		List<Candidate> found = query.find(List.of("과학", "잠들기"),
				List.of(new Target(1, 1), new Target(2, 5), new Target(3, 2), new Target(4, 9)), 30);

		assertThat(found).containsExactly(new Candidate(3, 2, 2), new Candidate(1, 2, 1), new Candidate(2, 1, 5));
	}

	@Test
	void tiesAreCutAfterTheOrderNotByBookIdFirst() {
		List<Book> books = new ArrayList<>();
		List<Target> targets = new ArrayList<>();
		for (int id = 1; id <= 40; id++) {
			books.add(new Book(id, "소설 " + id, null));
			targets.add(new Target(id, id == 40 ? 7 : 1));
		}
		store(books, List.of());

		List<Candidate> found = query.find(List.of("소설"), targets, 30);

		assertThat(found).hasSize(30);
		assertThat(found.getFirst()).isEqualTo(new Candidate(40, 1, 7));
		assertThat(found.get(1).bookId()).isEqualTo(1);
	}

	@Test
	void booksNoPlanLibraryHoldsAreLeftOut() {
		store(List.of(new Book(1, "과학", null), new Book(2, "과학", null)), List.of());

		assertThat(query.find(List.of("과학"), List.of(new Target(1, 0), new Target(2, 1)), 30))
			.extracting(Candidate::bookId)
			.containsExactly(2L);
		assertThat(query.find(List.of("과학"), List.of(), 30)).isEmpty();
	}

	@Test
	void percentAndUnderscoreMatchOnlyThemselves() {
		store(List.of(new Book(1, "100% 실화", null), new Book(2, "100점 실화", null), new Book(3, "a_b", null),
				new Book(4, "axb", null)), List.of());
		List<Target> all = List.of(new Target(1, 1), new Target(2, 1), new Target(3, 1), new Target(4, 1));

		assertThat(query.find(List.of("100%"), all, 30)).extracting(Candidate::bookId).containsExactly(1L);
		assertThat(query.find(List.of("a_b"), all, 30)).extracting(Candidate::bookId).containsExactly(3L);
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

	@Test
	void planTargetsCountThePlanLibrariesAndSkipShownBooks() {
		List<ListedBook> books = List.of(listed(1, 10, 11, 12), listed(2, 99), listed(3, 10));

		assertThat(PlanTargets.of(books, Set.of(10L, 12L), Set.of(3L))).containsExactly(new Target(1, 2));
		assertThat(PlanTargets.of(books, Set.of(), Set.of())).isEmpty();
	}

	private void store(List<Book> books, List<Line> lines) {
		texts.replace(books, lines);
	}

	private static ListedBook listed(long id, long... libraries) {
		return new ListedBook(id, "책", null, null, libraries, List.of("kaist"));
	}

}
