package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.AiSearchIndex.Candidate;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.Source;
import com.chaekcheck.catalog.BookTextsTransfer.Written;

/**
 * The BM25 keyword match (user 2026-10-30, {@code chaekcheck.catalog.ai-search.keyword-match: bm25}) on a test
 * database with pg_search (docker/postgres-bigm); skipped where the image has none (catalog/006 then makes no index).
 * A rare search term outranks common words, the plan-library filter and the books shown before are applied, and the
 * summary and terms are stored with the BM25 text.
 */
@SpringBootTest(properties = "chaekcheck.catalog.ai-search.keyword-match=bm25")
@Transactional
class AiSearchIndexBm25Tests {

	private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

	private static final Set<Long> PLAN = Set.copyOf(LongStream.rangeClosed(101, 110).boxed().toList());

	@Autowired
	AiSearchIndex index;

	@Autowired
	BookTextsTransfer transfer;

	@Autowired
	JdbcClient jdbc;

	@BeforeEach
	void needsTheIndex() {
		assumeTrue(jdbc.sql("SELECT to_regclass('catalog.book_search_bm25') IS NOT NULL").query(Boolean.class).single(),
				"no pg_search in this test database");
	}

	@Test
	void aRareSearchTermFindsItsBookFirst() {
		books(book(1, "코딩 테스트 대비", 3), book(2, "알고리즘 산책", 9), book(3, "요리 이야기", 9));
		store(List.of(new Line(930001, Source.INTRO, DAY, "취업 코딩 테스트를 준비하는 알고리즘 문제집")),
				List.of(new Written(930001, DAY, "코딩 테스트 문제와 풀이를 담은 책.",
						"코딩 테스트, 그리디, 최단 경로, 다익스트라, 플로이드 워셜, 알고리즘"),
						new Written(930002, DAY, "알고리즘의 역사를 쉽게 풀어 쓴 교양서.", "알고리즘, 역사, 교양, 수학")));

		List<Candidate> found = index.find(List.of("플로이드", "워셜", "알고리즘"), Set.of(), PLAN, Set.of(), 30);

		assertThat(found).extracting(Candidate::bookId).containsExactly(930001L, 930002L);
		assertThat(found.getFirst().planCount()).isEqualTo(3);
		assertThat(found.getFirst().score()).isGreaterThan(found.get(1).score());
	}

	@Test
	void theFilterAndTheShownBooksAreApplied() {
		books(book(1, "고양이 그림책", 2), book(2, "고양이 사진집", 0), book(3, "고양이 소설", 4));
		store(List.of(), List.of(new Written(930003, DAY, null, "고양이, 반려묘, 소설")));

		assertThat(index.find(List.of("고양이"), Set.of(), PLAN, Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactlyInAnyOrder(930001L, 930003L);
		assertThat(index.find(List.of("고양이"), Set.of(), PLAN, Set.of(930003L), 30)).extracting(Candidate::bookId)
			.containsExactly(930001L);
		assertThat(index.find(List.of("고양이"), Set.of(), Set.of(999L), Set.of(), 30)).extracting(Candidate::bookId)
			.containsExactlyInAnyOrder(930001L, 930002L, 930003L);
	}

	@Test
	void theWrittenTextsAreStoredAndAnOlderFileCannotRemoveThem() {
		books(book(1, "코스모스", 1));
		store(List.of(new Line(930001, Source.INTRO, DAY, "우주  이야기")),
				List.of(new Written(930001, DAY, "우주와 생명의 역사를 다룬 과학 교양서.", "우주, 천문학, 칼 세이건")));

		assertThat(jdbc.sql("SELECT summary || '|' || terms || '|' || keyword_text || '|' || library_words "
				+ "FROM catalog.book_search").query(String.class).single())
			.isEqualTo("우주와 생명의 역사를 다룬 과학 교양서.|우주, 천문학, 칼 세이건|코스모스 우주 이야기 우주, 천문학, 칼 세이건|l101 l999");
		assertThatThrownBy(() -> store(List.of(new Line(930001, Source.INTRO, DAY, "우주 이야기")), List.of()))
			.isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> new Written(930001, DAY, null, null)).isInstanceOf(IllegalArgumentException.class);
		assertThatThrownBy(() -> new Written(930001, DAY, "가".repeat(501), null))
			.isInstanceOf(IllegalArgumentException.class);
	}

	private void store(List<Line> lines, List<Written> written) {
		List<Long> ids = jdbc.sql("SELECT id FROM catalog.book WHERE id BETWEEN 930000 AND 939999 ORDER BY id")
			.query(Long.class)
			.list();
		transfer.replaceSearchBooks(ids, lines, written);
	}

	private void books(String... rows) {
		jdbc.sql("INSERT INTO catalog.book (id, title, title_norm, main_title_norm, content_type, library_ids) VALUES "
				+ String.join(", ", rows)).update();
	}

	/** Book 930000 + {@code n}, held by {@code planCount} of the plan libraries and library 999 outside the plan. */
	private static String book(int n, String title, int planCount) {
		List<String> libraries = new ArrayList<>();
		LongStream.rangeClosed(101, 100 + planCount).forEach(id -> libraries.add(String.valueOf(id)));
		libraries.add("999");
		return "(" + (930000 + n) + ", '" + title + "', 'x', 'x', 'EBOOK', '{" + String.join(",", libraries) + "}')";
	}

}
