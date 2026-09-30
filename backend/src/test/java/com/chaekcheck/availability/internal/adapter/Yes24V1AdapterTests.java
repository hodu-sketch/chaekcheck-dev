package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.nio.charset.Charset;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;

/**
 * Fixtures: 전주시립도서관 {@code http://ebook.jeonju.go.kr:8095}, 2026-09-28, stored as the EUC-KR bytes the site
 * sent (Content-Type without charset).
 */
class Yes24V1AdapterTests {

	Yes24V1Adapter adapter = new Yes24V1Adapter(AdapterFixtures.PROPERTIES_10);

	@Test
	void searchTextIsEucKrAndTenRowsAreAsked() { // AC-3, AC-19
		URI uri = adapter.searchUri("http://ebook.jeonju.go.kr:8095", SearchQuery.of("사랑"), 1);

		assertThat(uri.toString()).isEqualTo("http://ebook.jeonju.go.kr:8095/search/"
				+ "?src_key=%BB%E7%B6%FB&srch_order=title&sort=title&view=10&page_num=1");
	}

	@Test
	void authorAndPublisherFieldsInEucKr() { // AC-1, AC-2
		assertThat(adapter.searchUri("http://ebook.jeonju.go.kr:8095", SearchQuery.of("김달", SearchField.AUTHOR), 1)
			.getRawQuery()).isEqualTo("src_key=%B1%E8%B4%DE&srch_order=author&sort=title&view=10&page_num=1");
		assertThat(adapter.searchUri("http://ebook.jeonju.go.kr:8095", SearchQuery.of("빅피시", SearchField.PUBLISHER), 2)
			.getRawQuery()).isEqualTo("src_key=%BA%F2%C7%C7%BD%C3&srch_order=publisher&sort=title&view=10&page_num=2");
	}

	@Test
	void spacesAreEncoded() {
		URI uri = adapter.searchUri("http://x.test", SearchQuery.of("채식 주의자"), 1);

		assertThat(uri.getRawQuery()).startsWith("src_key=%C3%A4%BD%C4%20%C1%D6%C0%C7%C0%DA&");
	}

	@Test
	void readsEucKrPageWithoutCharsetHeader() { // AC-13, AC-19
		ParsedPage page = adapter.parse(bytes("yes24-v1/love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(27);
		assertThat(page.items()).hasSize(10);
		assertThat(page.items().getFirst()).isEqualTo(ParsedItem.counted("THE 궁합, 사주 사랑이 운명이 되다", "최제현 저",
				"가넷북스", "YES24", 1, 0, 0, "/ebook/detail/?goods_id=118074092"));
	}

	@Test
	void declaredCharsetWins() {
		ParsedPage page = adapter.parse(bytes("yes24-v1/love-p1.html"), Charset.forName("EUC-KR"));

		assertThat(page.items().getFirst().title()).isEqualTo("THE 궁합, 사주 사랑이 운명이 되다");
	}

	@Test
	void secondPage() { // AC-5
		ParsedPage page = adapter.parse(bytes("yes24-v1/love-p2.html"), null);

		assertThat(page.items()).hasSize(10);
		assertThat(page.items().getFirst().title()).isEqualTo("러브 앤 크라프트, 풍요실버타운의 사랑");
	}

	@Test
	void noResults() {
		ParsedPage page = adapter.parse(bytes("yes24-v1/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void detailPageCounts() { // cycle 04 parseDetail, page saved 2026-09-30
		assertThat(adapter.parseDetail(bytes("yes24-v1/detail-1.html"), null))
			.isEqualTo(ParsedDetail.counted(1, 0, 0));
	}

}
