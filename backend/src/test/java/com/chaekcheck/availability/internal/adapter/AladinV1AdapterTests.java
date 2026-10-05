package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Aladin e-library HTML (cycle 07, ADR-0056). Fixtures saved 2026-10-05 from 울산과학대학교 (uc.aladinebook.co.kr),
 * covers removed.
 */
class AladinV1AdapterTests {

	static final String BASE = "https://uc.aladinebook.co.kr?nav_code=ebo1655356114";

	AladinV1Adapter adapter = new AladinV1Adapter();

	@Test
	void searchUrlPerFieldKeepsTheMenuCode() { // AC-24, publisher too (user decision 2026-10-05)
		assertThat(adapter.searchUri(BASE, SearchQuery.of("사랑"), 1).toString())
			.isEqualTo("https://uc.aladinebook.co.kr/sub/sub.htm?nav_code=ebo1655356114&code=search"
					+ "&search_item=title&search_order=%EC%82%AC%EB%9E%91&view_level=1");
		assertThat(adapter.searchUri("http://lib.seojeong.ac.kr:8888?nav_code=ebo1655356114",
				SearchQuery.of("유창민", SearchField.AUTHOR), 1).toString())
			.startsWith("http://lib.seojeong.ac.kr:8888/sub/sub.htm?nav_code=ebo1655356114&code=search"
					+ "&search_item=author&");
		assertThat(adapter.searchUri(BASE, SearchQuery.of("포레스트 웨일", SearchField.PUBLISHER), 1).getRawQuery())
			.contains("search_item=publisher");
		assertThatThrownBy(() -> adapter.searchUri("https://uc.aladinebook.co.kr", SearchQuery.of("사랑"), 1))
			.isInstanceOf(UpstreamException.class);
	}

	@Test
	void firstPageRowsWithCounts() { // AC-8, AC-24
		ParsedPage page = adapter.parse(bytes("aladin-v1/search-love.html"), null);

		// the site says 63; only the first page is read, so no "더 보기"
		assertThat(page.totalCount()).isEqualTo(3);
		assertThat(page.items()).hasSize(3);
		ParsedItem first = page.items().getFirst();
		assertThat(first).extracting(ParsedItem::title, ParsedItem::author, ParsedItem::publisher,
				ParsedItem::holdings, ParsedItem::loaned, ParsedItem::reservations, ParsedItem::detailPath)
			.containsExactly("사랑과 타박상", "유창민 지음", "포레스트 웨일", 2, 0, 0, "353793204");
		URI search = adapter.searchUri(BASE, SearchQuery.of("사랑"), 1);
		assertThat(adapter.detailUri(search, first.detailPath()).toString())
			.isEqualTo("https://uc.aladinebook.co.kr/sub/sub.htm?mode=view&nav_code=ebo1655356114&goods_id=353793204");
	}

	@Test
	void detailCountsWithOrWithoutTheRunningTotal() { // AC-8
		assertThat(adapter.parseDetail(bytes("aladin-v1/view.html"), null)).isEqualTo(ParsedDetail.counted(2, 0, 0));
		assertThat(adapter.parseDetail(utf8("""
				<ul><li><span class="tt">보유현황</span><span>보유 <em>1</em>, 대출 <em>1</em>, 예약 <em>0</em>,
				누적대출 <em>32</em></span></li></ul>"""), null)).isEqualTo(ParsedDetail.counted(1, 1, 0));
		assertThatThrownBy(() -> adapter.parseDetail(utf8("<html><body>점검 중</body></html>"), null))
			.isInstanceOf(UpstreamException.class);
	}

}
