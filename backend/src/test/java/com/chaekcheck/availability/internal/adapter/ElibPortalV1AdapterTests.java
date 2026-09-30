package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Fixtures (2026-09-28, search text "사랑"): Busan / Gyeonggi fill the list verdict, Gyeongbuk / Incheon leave it
 * blank, table.do pages leave it blank everywhere. Detail pages from Gyeongbuk and Jeonbuk.
 */
class ElibPortalV1AdapterTests {

	ElibPortalV1Adapter adapter = new ElibPortalV1Adapter();

	@Test
	void firstPageIsIndexAndLaterPagesAreTheTableFragment() {
		assertThat(adapter.searchUri("https://library.busan.go.kr/elib", SearchQuery.of("사랑"), 1).toString())
			.isEqualTo("https://library.busan.go.kr/elib/module/elib/search/index.do?menu_idx=10"
					+ "&search_text=%EC%82%AC%EB%9E%91&rowCount=10&viewPage=1");
		assertThat(adapter.searchUri("https://library.busan.go.kr/elib", SearchQuery.of("사랑"), 2).toString())
			.isEqualTo("https://library.busan.go.kr/elib/module/elib/search/table.do?menu_idx=10&type=&search_type="
					+ "&author_name=&book_pubname=&book_year=&device=&search_text=%EC%82%AC%EB%9E%91&rowCount=10&viewPage=2");
	}

	static final String KIMDAL = "%EA%B9%80%EB%8B%AC";

	static final String BIGFISH = "%EB%B9%85%ED%94%BC%EC%8B%9C";

	@ParameterizedTest
	@ValueSource(ints = { 1, 2 })
	void authorSearchIsTheTableFragmentWithTheAuthorFilterFromPageOne(int page) { // AC-1, AC-4, ADR-0026
		assertThat(adapter.searchUri("https://library.busan.go.kr/elib", SearchQuery.of("김달", SearchField.AUTHOR), page)
			.toString()).isEqualTo("https://library.busan.go.kr/elib/module/elib/search/table.do?menu_idx=10&type="
					+ "&search_type=&author_name=" + KIMDAL + "&book_pubname=&book_year=&device=&search_text=" + KIMDAL
					+ "&rowCount=10&viewPage=" + page);
	}

	@ParameterizedTest
	@ValueSource(ints = { 1, 2 })
	void publisherSearchIsTheTableFragmentWithThePublisherFilterFromPageOne(int page) { // AC-2
		assertThat(adapter.searchUri("https://lib.jbe.go.kr/elib", SearchQuery.of("빅피시", SearchField.PUBLISHER), page)
			.toString()).isEqualTo("https://lib.jbe.go.kr/elib/module/elib/search/table.do?menu_idx=10&type="
					+ "&search_type=&author_name=&book_pubname=" + BIGFISH + "&book_year=&device=&search_text=" + BIGFISH
					+ "&rowCount=10&viewPage=" + page);
	}

	@ParameterizedTest
	@EnumSource(SearchField.class)
	void searchTypeIsAlwaysEmptyAndNeverAMaterialKind(SearchField field) { // AC-2
		for (int page = 1; page <= 2; page++) {
			URI uri = adapter.searchUri("https://lib.goe.go.kr/elib", SearchQuery.of("김달", field), page);
			assertThat(uri.getRawQuery()).doesNotContain("search_type=AUTHOR", "search_type=TITLE", "search_type=PUBLER")
				.doesNotContain("AUTHOR", "PUBLER");
			if (uri.getPath().endsWith("table.do")) {
				assertThat(uri.getRawQuery()).contains("&search_type=&");
			}
		}
	}

	@Test
	void menuIdOfTheBaseUrlIsUsedForAuthorSearchToo() { // Gangwon
		URI uri = adapter.searchUri("https://ebook.gwe.go.kr/elib?menu_idx=2", SearchQuery.of("김달", SearchField.AUTHOR),
				1);

		assertThat(uri.getPath()).isEqualTo("/elib/module/elib/search/table.do");
		assertThat(uri.getRawQuery()).startsWith("menu_idx=2&type=&search_type=&author_name=" + KIMDAL + "&");
	}

	@Test
	void authorFilterPageOfBusan() { // exact author "김달": 3 rows, blank verdicts
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/busan-table-author-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(3);
		assertThat(page.items()).extracting(ParsedItem::author).containsOnly("김달");
		assertThat(page.items()).extracting(ParsedItem::siteVerdict).containsOnly(SiteVerdict.BLANK);
		assertThat(page.items().get(1)).isEqualTo(new ParsedItem("사랑하기 전에 알았더라면 좋았을 것들", "김달", "빅피시", null,
				null, 0, 0, "../book/view.do?menu_idx=10&book_idx=19745&type=EBK", SiteVerdict.BLANK));
		assertThat(page.items().get(2).loaned()).isEqualTo(1);
	}

	@Test
	void publisherFilterPageOfJeonbukHasItsTotalOnlyInTheSearchInfoScript() { // 2026-09-29, no counts like Gyeongbuk
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/jbe-table-publisher-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(58);
		assertThat(page.items()).hasSize(10).extracting(ParsedItem::publisher).containsOnly("빅피시");
		assertThat(page.items().getFirst()).isEqualTo(new ParsedItem("내 아이의 사회성", "지니 킴 저", "빅피시", null, null,
				null, null, "../book/view.do?menu_idx=10&book_idx=88870&type=EBK", SiteVerdict.BLANK));
	}

	@Test
	void librarySearchUrlOfAuthorAndPublisherIsTheSearchPageNotTheFragment() { // table.do has no layout
		SearchQuery author = SearchQuery.of("김달", SearchField.AUTHOR);
		URI fetched = adapter.searchUri("https://library.busan.go.kr/elib", author, 2);

		assertThat(adapter.librarySearchUri("https://library.busan.go.kr/elib", author, fetched).toString())
			.isEqualTo("https://library.busan.go.kr/elib/module/elib/search/index.do?menu_idx=10&search_text=" + KIMDAL);
		assertThat(adapter.librarySearchUri("https://ebook.gwe.go.kr/elib?menu_idx=2",
				SearchQuery.of("빅피시", SearchField.PUBLISHER),
				adapter.searchUri("https://ebook.gwe.go.kr/elib?menu_idx=2", SearchQuery.of("빅피시", SearchField.PUBLISHER), 1))
			.toString())
			.isEqualTo("https://ebook.gwe.go.kr/elib/module/elib/search/index.do?menu_idx=2&search_text=" + BIGFISH);
	}

	@Test
	void librarySearchUrlOfATitleFirstPageIsThePageItself() {
		URI fetched = adapter.searchUri("https://library.busan.go.kr/elib", SearchQuery.of("사랑"), 1);

		assertThat(adapter.librarySearchUri("https://library.busan.go.kr/elib", SearchQuery.of("사랑"), fetched))
			.isEqualTo(fetched);
	}

	@Test
	void detailUrlOfAnAuthorRowIsTheSameAsForTitleRows() { // table.do and index.do are in one folder
		URI fetched = adapter.searchUri("https://library.busan.go.kr/elib", SearchQuery.of("김달", SearchField.AUTHOR), 1);

		assertThat(adapter.detailUri(fetched, "../book/view.do?menu_idx=10&book_idx=19745&type=EBK").toString())
			.isEqualTo("https://library.busan.go.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=19745&type=EBK");
	}

	@Test
	void listVerdictAndCounts() { // AC-14
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/busan-love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(279);
		assertThat(page.items()).hasSize(10);
		ParsedItem first = page.items().getFirst();
		assertThat(first).isEqualTo(new ParsedItem("사랑에 대한 작은 책", "울프 스타르크", "책빛", null, null, 0, 0,
				"../book/view.do?menu_idx=10&book_idx=644&type=EBK", SiteVerdict.AVAILABLE));
		assertThat(page.items().get(2).loaned()).isEqualTo(1);
	}

	@Test
	void labelledRowsOfGyeonggi() {
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/goe-love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(535);
		assertThat(page.items().getFirst()).extracting(ParsedItem::title, ParsedItem::author, ParsedItem::publisher,
				ParsedItem::loaned, ParsedItem::reservations, ParsedItem::siteVerdict)
			.containsExactly("사랑이 있으니 살아집디다", "유시민", "은빛", 2, 0, SiteVerdict.AVAILABLE);
	}

	@Test
	void blankVerdictOfGyeongbukAndIncheon() { // AC-15
		ParsedPage gb = adapter.parse(bytes("elib-portal-v1/gb-love-p1.html"), null);
		ParsedPage ice = adapter.parse(bytes("elib-portal-v1/ice-love-p1.html"), null);

		assertThat(gb.totalCount()).isEqualTo(665);
		assertThat(gb.items()).allSatisfy(item -> assertThat(item.siteVerdict()).isEqualTo(SiteVerdict.BLANK));
		assertThat(gb.items().getFirst()).extracting(ParsedItem::publisher, ParsedItem::loaned)
			.containsExactly("한국문학방송", 0);
		assertThat(ice.totalCount()).isEqualTo(154);
		assertThat(ice.items()).extracting(ParsedItem::siteVerdict).containsOnly(SiteVerdict.BLANK);
		assertThat(ice.items().get(1).loaned()).isEqualTo(1);
	}

	@Test
	void tablePagesHaveBlankVerdictsAndGyeongbukHasNoCounts() { // AC-5
		ParsedPage busan = adapter.parse(bytes("elib-portal-v1/busan-table-love-p2.html"), null);
		ParsedPage gb = adapter.parse(bytes("elib-portal-v1/gb-table-love-p1.html"), null);

		assertThat(busan.totalCount()).isEqualTo(279);
		assertThat(busan.items()).hasSize(10).extracting(ParsedItem::siteVerdict).containsOnly(SiteVerdict.BLANK);
		assertThat(busan.items().getFirst().detailPath()).isEqualTo("../book/view.do?menu_idx=10&book_idx=1129&type=EBK");
		assertThat(gb.items()).allSatisfy(item -> {
			assertThat(item.siteVerdict()).isEqualTo(SiteVerdict.BLANK);
			assertThat(item.loaned()).isNull();
		});
	}

	@Test
	void noResults() {
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/busan-none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void detailVerdicts() { // AC-15
		assertThat(adapter.parseDetail(bytes("elib-portal-v1/detail-available.html"), null).siteVerdict())
			.isEqualTo(SiteVerdict.AVAILABLE);
		assertThat(adapter.parseDetail(bytes("elib-portal-v1/detail-reservable.html"), null).siteVerdict())
			.isEqualTo(SiteVerdict.RESERVABLE);
		assertThat(adapter.parseDetail(bytes("elib-portal-v1/detail-reservable-jbe.html"), null).siteVerdict())
			.isEqualTo(SiteVerdict.RESERVABLE);
		assertThat(adapter.parseDetail(bytes("elib-portal-v1/detail-unknown.html"), null).siteVerdict())
			.isEqualTo(SiteVerdict.OTHER);
	}

	@Test
	void detailPageWithoutVerdictIsAnError() {
		assertThatThrownBy(() -> adapter.parseDetail(AdapterFixtures.utf8("<html>login</html>"), null).siteVerdict())
			.isInstanceOf(UpstreamException.class);
	}

	@Test
	void detailPathResolvesAgainstTheSearchPage() {
		URI search = URI.create("https://www.gbelib.kr/elib/module/elib/search/index.do?menu_idx=10&search_text=x");

		assertThat(search.resolve("../book/view.do?menu_idx=10&book_idx=63941&type=EBK").toString())
			.isEqualTo("https://www.gbelib.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=63941&type=EBK");
	}

	@Test
	void menuIdInTheBaseUrlIsUsedForSearchAndDetailPages() { // Gangwon, 2026-09-29
		URI search = adapter.searchUri("https://ebook.gwe.go.kr/elib?menu_idx=2", SearchQuery.of("사랑"), 1);
		URI table = adapter.searchUri("https://ebook.gwe.go.kr/elib?menu_idx=2", SearchQuery.of("사랑"), 2);

		assertThat(search.toString()).isEqualTo("https://ebook.gwe.go.kr/elib/module/elib/search/index.do?menu_idx=2"
				+ "&search_text=%EC%82%AC%EB%9E%91&rowCount=10&viewPage=1");
		assertThat(table.getQuery()).startsWith("menu_idx=2&type=");
		ParsedPage page = adapter.parse(bytes("elib-portal-v1/gwe-love-p1.html"), null);
		assertThat(page.totalCount()).isEqualTo(222);
		assertThat(adapter.detailUri(search, page.items().getFirst().detailPath()).toString())
			.isEqualTo("https://ebook.gwe.go.kr/elib/module/elib/book/view.do?menu_idx=2&book_idx=5693&type=EBK");
	}

	@Test
	void withoutAMenuIdInTheBaseUrlDetailPagesKeepMenuTen() {
		URI search = adapter.searchUri("https://www.gbelib.kr/elib", SearchQuery.of("사랑"), 1);

		assertThat(adapter.detailUri(search, "../book/view.do?menu_idx=10&book_idx=1&type=EBK").toString())
			.isEqualTo("https://www.gbelib.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=1&type=EBK");
	}

}
