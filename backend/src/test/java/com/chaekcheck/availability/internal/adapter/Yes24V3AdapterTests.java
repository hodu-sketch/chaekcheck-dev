package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;

/** Fixtures: 김천시립도서관 http://ebook.gcl.go.kr, "사랑", 2026-09-29. */
class Yes24V3AdapterTests {

	Yes24V3Adapter adapter = new Yes24V3Adapter(AdapterFixtures.PROPERTIES);

	@Test
	void buildsSearchUriWithEucKrQuery() {
		assertThat(adapter.searchUri("http://ebook.gcl.go.kr", SearchQuery.of("사랑"), 2))
			.hasToString("http://ebook.gcl.go.kr/search/?src_key=%BB%E7%B6%FB&srch_order=title&page_num=2&sort=title");
	}

	@Test
	void authorAndPublisherFieldsInEucKr() { // AC-1, AC-2
		assertThat(adapter.searchUri("http://ebook.gcl.go.kr", SearchQuery.of("김달", SearchField.AUTHOR), 1))
			.hasToString("http://ebook.gcl.go.kr/search/?src_key=%B1%E8%B4%DE&srch_order=author&page_num=1&sort=title");
		assertThat(adapter.searchUri("http://ebook.gcl.go.kr", SearchQuery.of("빅피시", SearchField.PUBLISHER), 1))
			.hasToString(
					"http://ebook.gcl.go.kr/search/?src_key=%BA%F2%C7%C7%BD%C3&srch_order=publisher&page_num=1&sort=title");
	}

	@Test
	void parsesAFullPageOfTheV2LayoutInEucKr() {
		ParsedPage page = adapter.parse(bytes("yes24-v3/love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(119);
		assertThat(page.items()).hasSize(10);
		assertThat(page.items().getFirst()).isEqualTo(ParsedItem.counted("21세기 마지막 첫사랑", "김빵 저", "자이언트북스",
				"예스이십사", 2, 0, 0, "/ebook/detail/?goods_id=128536516"));
	}

	@Test
	void parsesTheSecondPage() {
		ParsedPage page = adapter.parse(bytes("yes24-v3/love-p2.html"), null);

		assertThat(page.totalCount()).isEqualTo(119);
		assertThat(page.items()).hasSize(10);
	}

	@Test
	void parsesNoResults() {
		ParsedPage page = adapter.parse(bytes("yes24-v3/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void detailPageCounts() { // cycle 04 parseDetail, page saved 2026-09-30
		assertThat(adapter.parseDetail(bytes("yes24-v3/detail-1.html"), null))
			.isEqualTo(ParsedDetail.counted(2, 0, 0));
	}

}
