package com.chaekcheck.popular.internal.fetch;

import static com.chaekcheck.popular.internal.fetch.KpipaFixtures.html;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chaekcheck.popular.internal.PopularCategory;

/** Ranking and sales answers, and the display order (plan cycle 10 step 3; fixtures from step 0). */
class PopularParserTests {

	@Test
	void fiftyBooksInListOrder() {
		List<RankedBook> books = RankPageParser.parse(html("rank-search-all.html"), "전체");

		assertThat(books).hasSize(50).extracting(RankedBook::rank).startsWith(1, 2, 3).endsWith(50);
		assertThat(books.getFirst()).isEqualTo(new RankedBook(1, "9791199489561", "세네카, 오늘을 빼앗기고 있는 당신에게",
				"철학", "/files/isbn/2026/07/06/s_a455c2047d9945bc9ca9a7a2cec718c9.jpg"));
		assertThat(books.get(1).title()).isEqualTo("수족관");
		assertThat(books.getLast()).isEqualTo(new RankedBook(50, "9788917243796",
				"ETS 토익 정기시험 기출문제집 1000 Vol. 5 LC", "어학",
				"/files/isbn/2025/12/16/s_32e0906ab2af4936b9d6d28ff9e17ed1.jpg"));
		assertThat(RankPageParser.missingIsbnCount(books)).isZero();
		// the record has no author: .info .writer is not read
		assertThat(Arrays.stream(RankedBook.class.getRecordComponents()).map(c -> c.getName()))
			.doesNotContain("author", "writer");
	}

	@Test
	void smallCoverFromSrcInEveryFolderOfTheSite() {
		List<RankedBook> books = RankPageParser.parse(html("rank-search-all.html"), "전체");

		// the small cover is the src itself (data-originalsrc is the big one)
		assertThat(books).extracting(RankedBook::coverPath).allMatch(path -> path.contains("/s_")).doesNotContainNull();
		assertThat(books.get(3).coverPath()).isEqualTo("/files/old/2002/01/20/s_8937460580_3.jpg");
		assertThat(books.get(15).coverPath()).isEqualTo("/files/onix/2026/01/22/s_20260122163104-1367167192961414367.jpg");
		assertThat(books.get(46).coverPath()).isEqualTo("/files/trans/2021/05/04/s_20220731201043452969.jpg");
	}

	@Test
	void emptyOrBadIsbnAndUnsafeCoversAreNull() {
		List<RankedBook> books = RankPageParser.parse(html("rank-search-bad-values.html"), "전체");

		assertThat(books.get(2).isbn13()).isNull();
		assertThat(books.get(4).isbn13()).isNull();
		assertThat(RankPageParser.missingIsbnCount(books)).isEqualTo(2);
		assertThat(books.get(2).title()).isEqualTo("한국사 이상현상 연구원");
		assertThat(books.get(6).coverPath()).isNull();
		assertThat(books.get(7).coverPath()).isNull();
		assertThat(books.get(8).coverPath()).isNull();
		assertThat(books.get(9).coverPath()).isNotNull();
	}

	@Test
	void coverPathRule() {
		assertThat(cover("/files/isbn/2026/07/06/s_a.jpg")).isEqualTo("/files/isbn/2026/07/06/s_a.jpg");
		assertThat(cover("/files/isbn/2026/07/06/a.jpg")).isNull(); // the big cover
		assertThat(cover("//evil.test/files/isbn/s_a.jpg")).isNull();
		assertThat(cover("https://bnk.kpipa.or.kr/files/isbn/s_a.jpg")).isNull();
		assertThat(cover("/files/isbn//s_a.jpg")).isNull();
		assertThat(cover("/files/isbn/../s_a.jpg")).isNull();
		assertThat(cover("/files/isbn/s_a.jpg?x=1")).isNull();
		assertThat(cover("javascript:alert(1)")).isNull();
		assertThat(cover("")).isNull();
	}

	@Test
	void fewerThanFiftyMoreThanFiftyAndNoList() {
		assertThatThrownBy(() -> RankPageParser.parse(html("rank-search-49-books.html"), "소설"))
			.isInstanceOfSatisfying(FetchFailure.class, failure -> {
				assertThat(failure.reason()).isEqualTo(FailureReason.TOO_FEW_BOOKS);
				assertThat(failure.getMessage()).isEqualTo("소설: 49 books");
			});
		assertThatThrownBy(() -> RankPageParser.parse(html("rank-search-no-items.html"), "소설"))
			.isInstanceOfSatisfying(FetchFailure.class,
					failure -> assertThat(failure.reason()).isEqualTo(FailureReason.FORMAT_CHANGED));
		assertThatThrownBy(() -> RankPageParser.parse("<html><body>점검 중</body></html>", "소설"))
			.isInstanceOfSatisfying(FetchFailure.class,
					failure -> assertThat(failure.reason()).isEqualTo(FailureReason.FORMAT_CHANGED));

		String all = html("rank-search-all.html");
		int listEnd = all.lastIndexOf("</ul>");
		String extra = all.substring(0, listEnd)
				+ "<li><div class=\"book-grid-item\"><div class=\"figcaption\">51번째</div></div></li>"
				+ all.substring(listEnd);
		assertThat(RankPageParser.parse(extra, "전체")).hasSize(50).extracting(RankedBook::title).doesNotContain("51번째");
	}

	@Test
	void emptyTitleIsFormatChanged() {
		String all = html("rank-search-all.html");
		String noTitle = all.replaceFirst("(?s)(<div class=\"figcaption\">)\\s*세네카, 오늘을 빼앗기고 있는 당신에게\\s*",
				"$1");

		assertThatThrownBy(() -> RankPageParser.parse(noTitle, "전체"))
			.isInstanceOfSatisfying(FetchFailure.class, failure -> {
				assertThat(failure.reason()).isEqualTo(FailureReason.FORMAT_CHANGED);
				assertThat(failure.getMessage()).contains("rank 1");
			});
	}

	@Test
	void salesCountIsTheFirstDataX1() {
		assertThat(SalesCountParser.parse(html("sales-count-novel.html"), "소설")).isEqualTo(883_478L);
		assertThatThrownBy(() -> SalesCountParser.parse(html("sales-count-no-data.html"), "소설"))
			.isInstanceOfSatisfying(FetchFailure.class, failure -> {
				assertThat(failure.reason()).isEqualTo(FailureReason.FORMAT_CHANGED);
				assertThat(failure.getMessage()).isEqualTo("소설: dataX1 is not a number");
			});
		assertThatThrownBy(() -> SalesCountParser.parse("<script>var x = 1;</script>", "소설"))
			.isInstanceOfSatisfying(FetchFailure.class,
					failure -> assertThat(failure.getMessage()).isEqualTo("소설: no dataX1"));
		assertThatThrownBy(() -> SalesCountParser.parse("dataX1 = Number(\"8,834\");", "소설"))
			.isInstanceOf(FetchFailure.class);
	}

	/** AC-4: the 202609 counts (plan research kpipa.md 2-1절) give 전체, 소설, ... 지리·환경. */
	@Test
	void displayOrderBySalesCount() {
		Map<PopularCategory, Long> sales = new EnumMap<>(PopularCategory.class);
		sales.put(PopularCategory.ARTS, 213_287L);
		sales.put(PopularCategory.GEOGRAPHY, 7_908L);
		sales.put(PopularCategory.NOVEL, 883_478L);
		sales.put(PopularCategory.LITERATURE, 739_250L);
		sales.put(PopularCategory.SOCIETY, 731_208L);
		sales.put(PopularCategory.LANGUAGE, 463_935L);
		sales.put(PopularCategory.PHILOSOPHY, 406_420L);
		sales.put(PopularCategory.ECONOMY, 387_669L);
		sales.put(PopularCategory.SELF_HELP, 360_765L);
		sales.put(PopularCategory.COMPUTING, 152_493L);
		sales.put(PopularCategory.LIVING, 141_480L);
		sales.put(PopularCategory.SCIENCE, 130_289L);
		sales.put(PopularCategory.HISTORY, 89_508L);
		sales.put(PopularCategory.TECHNOLOGY, 61_812L);
		sales.put(PopularCategory.LAW, 21_547L);
		sales.put(PopularCategory.REFERENCE, 11_448L);

		Map<PopularCategory, Integer> order = CategoryOrder.of(sales);

		assertThat(order.entrySet()
			.stream()
			.sorted(Map.Entry.comparingByValue())
			.map(entry -> entry.getKey().shortName())
			.toList()).containsExactly("전체", "소설", "문학·인물", "사회", "언어", "철학·종교", "경제·경영", "자기계발", "예술",
					"컴퓨터·IT", "생활·취미", "과학", "역사", "기술·공학", "법", "참고·정보", "지리·환경");
	}

	@Test
	void equalCountsBySiteId() {
		Map<PopularCategory, Long> sales = new EnumMap<>(PopularCategory.class);
		PopularCategory.withSales().forEach(category -> sales.put(category, 5L));

		Map<PopularCategory, Integer> order = CategoryOrder.of(sales);

		assertThat(order.get(PopularCategory.ARTS)).isEqualTo(1); // ...0001
		assertThat(order.get(PopularCategory.LANGUAGE)).isEqualTo(2); // ...0178
		assertThat(order.get(PopularCategory.LIVING)).isEqualTo(16); // ...2476
	}

	private static String cover(String src) {
		return RankPageParser.coverPath(org.jsoup.Jsoup.parse("<img src='" + src + "'>").selectFirst("img"));
	}

}
