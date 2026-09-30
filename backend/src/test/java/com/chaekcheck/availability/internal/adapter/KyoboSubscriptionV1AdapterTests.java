package com.chaekcheck.availability.internal.adapter;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;

/** Fixtures: 세종시립도서관(구독) {@code https://sejonglib.dkyobobook.co.kr}, 2026-09-28. */
class KyoboSubscriptionV1AdapterTests {

	KyoboSubscriptionV1Adapter adapter = new KyoboSubscriptionV1Adapter(AdapterFixtures.PROPERTIES_10);

	@Test
	void searchUrlHasNoElibraryFrontAndAsksForTenRows() { // AC-3
		URI uri = adapter.searchUri("https://sejonglib.dkyobobook.co.kr", SearchQuery.of("사랑"), 2);

		assertThat(uri.toString()).isEqualTo("https://sejonglib.dkyobobook.co.kr/search/searchList.ink"
				+ "?schClst=ctts&schDvsn=000&schTxt=%EC%82%AC%EB%9E%91&recordCount=10&pageIndex=2");
		assertThat(adapter.pageSize()).isEqualTo(10);
	}

	@Test
	void authorAndPublisherFields() { // AC-1, AC-2
		assertThat(adapter.searchUri("https://sejonglib.dkyobobook.co.kr", SearchQuery.of("김달", SearchField.AUTHOR), 1)
			.getRawQuery()).isEqualTo("schClst=autr&schDvsn=000&schTxt=%EA%B9%80%EB%8B%AC&recordCount=10&pageIndex=1");
		assertThat(
				adapter.searchUri("https://sejonglib.dkyobobook.co.kr", SearchQuery.of("빅피시", SearchField.PUBLISHER), 1)
					.getRawQuery())
			.isEqualTo("schClst=pbcm&schDvsn=000&schTxt=%EB%B9%85%ED%94%BC%EC%8B%9C&recordCount=10&pageIndex=1");
	}

	@Test
	void rowsHaveNoCounts() { // AC-16
		ParsedPage page = adapter.parse(bytes("kyobo-subscription-v1/love-p1.html"), null);

		assertThat(page.totalCount()).isEqualTo(2294);
		assertThat(page.items()).hasSize(10).allSatisfy(item -> {
			assertThat(item.holdings()).isNull();
			assertThat(item.loaned()).isNull();
			assertThat(item.reservations()).isNull();
			assertThat(item.supplier()).isEqualTo("교보문고");
		});
		assertThat(page.items().getFirst()).extracting(ParsedItem::title, ParsedItem::author, ParsedItem::publisher,
				ParsedItem::detailPath)
			.containsExactly("오르페우스의 사랑", "이루미연구소", "이루미에듀테크",
					"/content/contentView.ink?cttsDvsnCode=001&brcd=480D250589450&ctgrId=330509&sntnAuthCode=");
	}

	@Test
	void secondPageHasOtherBooks() {
		ParsedPage first = adapter.parse(bytes("kyobo-subscription-v1/love-p1.html"), null);
		ParsedPage second = adapter.parse(bytes("kyobo-subscription-v1/love-p2.html"), null);

		assertThat(second.items()).hasSize(10);
		assertThat(second.items()).extracting(ParsedItem::detailPath)
			.doesNotContainAnyElementsOf(first.items().stream().map(ParsedItem::detailPath).toList());
	}

	@Test
	void noResults() {
		ParsedPage page = adapter.parse(bytes("kyobo-subscription-v1/none.html"), null);

		assertThat(page.totalCount()).isZero();
		assertThat(page.items()).isEmpty();
	}

	@Test
	void subscriptionDetailPagesAreNotRead() { // never requested [확정]
		org.assertj.core.api.Assertions.assertThatThrownBy(() -> adapter.parseDetail(new byte[0], null))
			.isInstanceOf(UnsupportedOperationException.class);
	}

}
