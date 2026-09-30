package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;

/** Fixtures: 강남구 전자도서관 {@code https://ebook.gangnam.go.kr}, 2026-09-28, EUC-KR bytes as sent. */
class GangnamV1AdapterTests {

	GangnamV1Adapter adapter = new GangnamV1Adapter();

	@Test
	void searchTextIsEucKrAndPagesAreTwentyRows() { // AC-19; the site ignores list_num=10
		URI uri = adapter.searchUri("https://ebook.gangnam.go.kr", SearchQuery.of("사랑"), 2);

		assertThat(uri.toString()).isEqualTo(
				"https://ebook.gangnam.go.kr/elibbook/book_info.asp?search=title&strSearch=%BB%E7%B6%FB&page_num=2");
		assertThat(adapter.pageSize()).isEqualTo(20);
	}

	@Test
	void authorAndPublisherFieldsInEucKr() { // AC-1, AC-2
		assertThat(adapter.searchUri("https://ebook.gangnam.go.kr", SearchQuery.of("김달", SearchField.AUTHOR), 1)
			.getRawQuery()).isEqualTo("search=author&strSearch=%B1%E8%B4%DE&page_num=1");
		assertThat(adapter.searchUri("https://ebook.gangnam.go.kr", SearchQuery.of("빅피시", SearchField.PUBLISHER), 1)
			.getRawQuery()).isEqualTo("search=publisher&strSearch=%BA%F2%C7%C7%BD%C3&page_num=1");
	}

	@Test
	void readsCountsAndSupplierPerRow() { // AC-13
		ParsedPage page = adapter.parse(bytes("gangnam-v1/love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(424);
		assertThat(page.items()).hasSize(20);
		assertThat(page.items().get(1)).isEqualTo(ParsedItem.counted("문제적 사랑", "김지용", "디플롯", "교보전자책", 2,
				2, 3, "book_detail.asp?book_num=KB008356"));
	}

	@Test
	void fullReservationQueueHasNoCounts() {
		ParsedPage page = adapter.parse(bytes("gangnam-v1/love-p1.html"), null);

		// "예약정원이 마감되었습니다." instead of the counts
		assertThat(page.items().getFirst()).isEqualTo(new ParsedItem("다른 사랑", "최은미", "문학동네", "교보전자책", null,
				null, null, "book_detail.asp?book_num=KB008336", SiteVerdict.OTHER));
	}

	@Test
	void secondPage() {
		ParsedPage page = adapter.parse(bytes("gangnam-v1/love-p2.html"), null);

		assertThat(page.items()).hasSize(20);
		assertThat(page.items().getFirst().title()).isEqualTo("지금 사랑한다고 말하세요");
	}

	@Test
	void noResults() {
		ParsedPage page = adapter.parse(bytes("gangnam-v1/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void detailPageCounts() { // cycle 04 parseDetail, page saved 2026-09-30
		assertThat(adapter.parseDetail(bytes("gangnam-v1/detail-1.html"), null))
			.isEqualTo(ParsedDetail.counted(5, 3, 10));
	}

	@Test
	void detailPageReadsOnlyTheBookNotTheRelatedBooks() {
		String page = new String(bytes("gangnam-v1/detail-1.html"), java.nio.charset.Charset.forName("EUC-KR"));
		// the related books below the book have their own blocks; their counts must not win
		org.assertj.core.api.Assertions.assertThat(page.split("class=\"current\"", -1)).hasSize(4);
		String closed = page.replaceFirst("class=\"current\"", "class=\"current close\"");
		assertThat(adapter.parseDetail(closed.getBytes(java.nio.charset.Charset.forName("EUC-KR")), null))
			.isEqualTo(new ParsedDetail(null, null, null, SiteVerdict.OTHER));
	}

}
