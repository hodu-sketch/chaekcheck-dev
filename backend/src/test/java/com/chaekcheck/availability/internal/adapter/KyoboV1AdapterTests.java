package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.utf8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;

class KyoboV1AdapterTests {

	KyoboV1Adapter adapter = new KyoboV1Adapter(AdapterFixtures.PROPERTIES);

	@Test
	void buildsSearchUriWithUtf8Query() {
		assertThat(adapter.searchUri("https://www.ghlib.go.kr:446", SearchQuery.of("사랑"), 2))
			.hasToString("https://www.ghlib.go.kr:446/elibrary-front/search/searchList.ink?schClst=ctts&schDvsn=000"
					+ "&schTxt=%EC%82%AC%EB%9E%91&recordCount=50&pageIndex=2");
	}

	@Test
	void mapsSearchFieldsToTheSiteClassification() {
		assertThat(adapter.searchUri("https://x", SearchQuery.of("한강", SearchField.AUTHOR), 1).getQuery())
			.contains("schClst=autr");
		assertThat(adapter.searchUri("https://x", SearchQuery.of("민음사", SearchField.PUBLISHER), 1).getQuery())
			.contains("schClst=pbcm");
	}

	@Test
	void parsesOneResult() {
		ParsedPage page = adapter.parse(bytes("kyobo-v1/veg-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(1);
		assertThat(page.items()).containsExactly(ParsedItem.counted(
				"왜 채식주의자인가.The Book of Why I Am a Vegetarian, by J. Howard Moore", "J. Howard Moore", "뉴가출판사",
				"교보문고", 1, 0, 0,
				"/elibrary-front/content/contentView.ink?cttsDvsnCode=001&brcd=480D200235740&ctgrId=2313&sntnAuthCode="));
	}

	@Test
	void parsesFiftyPerPageOnBothPages() {
		ParsedPage first = adapter.parse(bytes("kyobo-v1/love-p1.html"), null);
		ParsedPage second = adapter.parse(bytes("kyobo-v1/love-p2.html"), null);

		assertThat(first.totalCount()).isEqualTo(103);
		assertThat(first.items()).hasSize(50);
		assertThat(second.items()).hasSize(50).doesNotContainAnyElementsOf(first.items());
		assertThat(first.items()).allSatisfy(item -> {
			assertThat(item.title()).isNotBlank();
			assertThat(item.supplier()).isNotBlank();
			assertThat(item.loaned()).isBetween(0, item.holdings());
		});
	}

	@Test
	void parsesNoResults() {
		ParsedPage page = adapter.parse(bytes("kyobo-v1/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void failsOnUnexpectedPage() {
		assertThatThrownBy(() -> adapter.parse(utf8("<html><body>error</body></html>"), null))
			.isInstanceOf(UpstreamException.class);
	}

	@Test
	void detailPageCounts() { // cycle 04 parseDetail, page saved 2026-09-30
		assertThat(adapter.parseDetail(bytes("kyobo-v1/detail-1.html"), null))
			.isEqualTo(ParsedDetail.counted(1, 0, 0));
	}

}
