package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

/**
 * 서울도서관 JSON (library-maintenance 4-14). Fixtures saved 2026-10-20 from elib.seoul.go.kr with descriptions,
 * author notes and cover addresses removed; the title search keeps 3 of its 10 rows, the English one 4.
 */
class SeoulElibV1AdapterTests {

	static final String BASE = "https://elib.seoul.go.kr";

	SeoulElibV1Adapter adapter = new SeoulElibV1Adapter();

	@Test
	void searchUrlPerFieldWithPages() {
		assertThat(adapter.searchUri(BASE, SearchQuery.of("사랑"), 2).toString())
			.isEqualTo("https://elib.seoul.go.kr/api/contents/search?libCode=&contentType=EB"
					+ "&searchKeyword=%EC%82%AC%EB%9E%91&searchOption=1&sortOption=1&innerSearchYN=N&innerKeyword="
					+ "&currentCount=2&pageCount=10&loanable=&isTotal=false&showType=L&searchCombine=N");
		assertThat(adapter.searchUri(BASE, SearchQuery.of("김영하", SearchField.AUTHOR), 1).getRawQuery())
			.contains("&searchOption=2&");
		assertThat(adapter.searchUri(BASE, SearchQuery.of("문학동네", SearchField.PUBLISHER), 1).getRawQuery())
			.contains("&searchOption=3&");
		assertThat(adapter.searchUri(BASE, SearchQuery.of("a&b c"), 1).getRawQuery())
			.contains("searchKeyword=a%26b%20c&");
	}

	@Test
	void ownedRowsHaveCountsAndBookPages() {
		ParsedPage page = adapter.parse(bytes("seoul-elib/search-author-kimyoungha.json"), null);

		assertThat(page.totalCount()).isEqualTo(5);
		assertThat(page.items()).hasSize(5);
		ParsedItem owned = page.items().get(4);
		assertThat(owned).extracting(ParsedItem::title, ParsedItem::author, ParsedItem::publisher,
				ParsedItem::supplier, ParsedItem::holdings, ParsedItem::loaned, ParsedItem::reservations,
				ParsedItem::siteVerdict)
			.containsExactly("오직 두 사람", "김영하", "문학동네", "OPMS", 5, 5, 1, null);
		URI search = adapter.searchUri(BASE, SearchQuery.of("김영하", SearchField.AUTHOR), 1);
		URI book = adapter.detailUri(search, owned.detailPath());
		assertThat(book.toString()).isEqualTo("https://elib.seoul.go.kr/contents/detail.do?no=CPP0000597973");
		assertThat(adapter.detailFetchUri(book).toString())
			.isEqualTo("https://elib.seoul.go.kr/api/contents/CPP0000597973");
	}

	@Test
	void subscriptionRowsHaveNoCounts() { // TY02: "대출 수 제한 없음"; one row shows 2 loans on 1 copy
		ParsedPage page = adapter.parse(bytes("seoul-elib/search-title-love-p1.json"), null);

		assertThat(page.totalCount()).isEqualTo(354);
		assertThat(page.items()).extracting(ParsedItem::supplier, ParsedItem::holdings, ParsedItem::siteVerdict)
			.containsExactly(tuple("ECO", null, SiteVerdict.SUBSCRIPTION), tuple("ECO", null, SiteVerdict.SUBSCRIPTION),
					tuple("OPMS", 5, null));
	}

	@Test
	void overDriveRowsAreLeftOut() { // ownerCode OV: borrowed on seoullib.overdrive.com, not here
		ParsedPage page = adapter.parse(bytes("seoul-elib/search-title-love-en-p1.json"), null);

		assertThat(page.totalCount()).isEqualTo(72);
		assertThat(page.items()).extracting(ParsedItem::title)
			.containsExactly("30 Love Short Stories", "42:FirstLove&Mumu(첫사랑&무무)");
	}

	@Test
	void noResults() {
		ParsedPage page = adapter.parse(bytes("seoul-elib/search-none.json"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void audiobookRowsAreLeftOut() {
		String json = """
				{"totalCount":"1","ContentDataList":[{"contentsKey":"AD7033002","title":"기쁨","contentsType":"TY03",
				"ownerCode":"AD","currentLoanCount":4,"currentResvCount":0}]}""";

		assertThat(adapter.parse(utf8(json), null).items()).isEmpty();
	}

	@Test
	void detailCountsAndKinds() {
		assertThat(adapter.parseDetail(bytes("seoul-elib/detail-owned.json"), null))
			.isEqualTo(ParsedDetail.counted(5, 5, 1));
		assertThat(adapter.parseDetail(bytes("seoul-elib/detail-subscription.json"), null))
			.isEqualTo(ParsedDetail.verdict(SiteVerdict.SUBSCRIPTION));
		assertThat(adapter.parseDetail(bytes("seoul-elib/detail-overseas.json"), null))
			.isEqualTo(ParsedDetail.verdict(SiteVerdict.OTHER));
	}

	@Test
	void frontPageForPeople() {
		assertThat(adapter.librarySearchUri(BASE, SearchQuery.of("사랑"), URI.create(BASE + "/api/contents/search"))
			.toString()).isEqualTo("https://elib.seoul.go.kr/main");
	}

	@Test
	void otherShapesFail() {
		assertThatThrownBy(() -> adapter.parse(utf8("<!DOCTYPE html><html><title>서울도서관</title></html>"), null))
			.isInstanceOf(UpstreamException.class);
		assertThatThrownBy(() -> adapter.parse(utf8("{\"statusCode\":200}"), null))
			.isInstanceOf(UpstreamException.class);
		assertThatThrownBy(() -> adapter.parse(utf8("""
				{"totalCount":"1","ContentDataList":[{"contentsKey":"X","title":"t","contentsType":"TY01",
				"ownerCode":"BX","currentLoanCount":0,"currentResvCount":0}]}"""), null))
			.isInstanceOf(UpstreamException.class)
			.hasMessageContaining("b2bCopys");
		assertThatThrownBy(() -> adapter.parseDetail(utf8("{\"Contents\":[],\"statusCode\":200}"), null))
			.isInstanceOf(UpstreamException.class);
	}

}
