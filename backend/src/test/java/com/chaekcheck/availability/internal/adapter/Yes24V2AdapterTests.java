package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

class Yes24V2AdapterTests {

	Yes24V2Adapter adapter = new Yes24V2Adapter(AdapterFixtures.PROPERTIES);

	@Test
	void buildsSearchUriWithUtf8Query() {
		assertThat(adapter.searchUri("https://ebook.gangseo.seoul.kr", SearchQuery.of("채식주의자"), 2))
			.hasToString("https://ebook.gangseo.seoul.kr/search/?src_key=%EC%B1%84%EC%8B%9D%EC%A3%BC%EC%9D%98%EC%9E%90"
					+ "&srch_order=title&page_num=2&sort=title");
	}

	@Test
	void mapsSearchFieldsToTheSiteSearchOrder() {
		assertThat(adapter.searchUri("https://x", SearchQuery.of("김석희", SearchField.AUTHOR), 1).getQuery())
			.contains("srch_order=author");
		assertThat(adapter.searchUri("https://x", SearchQuery.of("민음사", SearchField.PUBLISHER), 1).getQuery())
			.contains("srch_order=publisher");
	}

	@Test
	void parsesOneResult() {
		ParsedPage page = adapter.parse(bytes("yes24-v2/veg-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(1);
		assertThat(page.items()).containsExactly(ParsedItem.counted("한강, 채식주의자 깊게 읽기",
				"정미숙,이귀우,한정희,주은경,한귀은 공저", "더스토리", "YES24", 1, 0, 0, "/ebook/detail/?goods_id=29090201"));
	}

	@Test
	void parsesFullPageOfTen() {
		ParsedPage page = adapter.parse(bytes("yes24-v2/love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(221);
		assertThat(page.items()).hasSize(10).allSatisfy(item -> {
			assertThat(item.title()).isNotBlank();
			assertThat(item.holdings()).isPositive();
		});
	}

	@Test
	void parsesNoResults() {
		ParsedPage page = adapter.parse(bytes("yes24-v2/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void failsOnUnexpectedPage() {
		assertThatThrownBy(() -> adapter.parse(utf8("<html><body>maintenance</body></html>"), null))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.ERROR));
	}

	@Test
	void detailPageCounts() { // cycle 04 parseDetail, page saved 2026-09-30
		assertThat(adapter.parseDetail(bytes("yes24-v2/detail-1.html"), null))
			.isEqualTo(ParsedDetail.counted(3, 0, 0));
	}

}
