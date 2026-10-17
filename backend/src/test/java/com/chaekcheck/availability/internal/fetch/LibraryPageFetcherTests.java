package com.chaekcheck.availability.internal.fetch;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.html;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.TestLanes;
import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.adapter.ElibPortalV1Adapter;
import com.chaekcheck.availability.internal.adapter.KyoboV1Adapter;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.availability.internal.adapter.Yes24V2Adapter;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.library.SearchTarget;
import com.github.benmanes.caffeine.cache.Ticker;

class LibraryPageFetcherTests {

	static final SearchTarget GOHEUNG = TestTargets.GOHEUNG;

	static final SearchTarget GANGSEO = TestTargets.GANGSEO;

	static final SearchTarget BUSAN = TestTargets.target(10, PlatformType.ELIB_PORTAL_V1,
			"https://library.busan.go.kr/elib");

	static final Duration DEADLINE = Duration.ofMillis(500);

	SiteFetcher client = mock(SiteFetcher.class);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	LibraryPageFetcher fetcher = new LibraryPageFetcher(
			List.of(new KyoboV1Adapter(AdapterFixtures.PROPERTIES), new Yes24V2Adapter(AdapterFixtures.PROPERTIES),
					new ElibPortalV1Adapter()),
			new CaffeineSearchPageCache(Duration.ofMinutes(3), 1000, executor, Ticker.systemTicker()),
			TestLanes.direct(client, Clock.systemUTC()), DEADLINE, Clock.systemUTC(),
			new BookKeyNormalizer(), new ParseSlots(8, Duration.ofSeconds(2)), new InMemoryHostQueueBoard());

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void detailLinkOffTheLibrarysHostIsDroppedFromThePageAndTheCache() throws Exception { // cycle 20, AC-18
		com.chaekcheck.availability.internal.adapter.PlatformAdapter adapter = mock(
				com.chaekcheck.availability.internal.adapter.PlatformAdapter.class);
		given(adapter.platform()).willReturn(PlatformType.ELIB_PORTAL_V1);
		given(adapter.pageSize()).willReturn(10);
		given(adapter.searchUri(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
			.willReturn(URI.create("https://library.busan.go.kr/elib/module/elib/search/index.do?menu_idx=10"));
		given(adapter.librarySearchUri(any(), any(), any())).willReturn(URI.create("https://library.busan.go.kr/elib"));
		given(adapter.detailUri(any(), any())).willCallRealMethod();
		given(adapter.parse(any(), any())).willReturn(new com.chaekcheck.availability.internal.adapter.ParsedPage(2,
				List.of(new com.chaekcheck.availability.internal.adapter.ParsedItem("같은 호스트", null, null, null, null,
								1, 0, "/elib/module/elib/book/view.do?book_idx=1",
								com.chaekcheck.availability.internal.adapter.SiteVerdict.BLANK),
						new com.chaekcheck.availability.internal.adapter.ParsedItem("다른 호스트", null, null, null, null,
								1, 0, "https://evil.test/elib/module/elib/book/view.do?book_idx=2",
								com.chaekcheck.availability.internal.adapter.SiteVerdict.BLANK))));
		given(client.fetch(any(), any(), any())).willReturn(ok("<html/>"));
		LibraryPageFetcher checked = new LibraryPageFetcher(List.of(adapter),
				new CaffeineSearchPageCache(Duration.ofMinutes(3), 1000, executor, Ticker.systemTicker()),
				TestLanes.direct(client, Clock.systemUTC()), DEADLINE, Clock.systemUTC(),
				new BookKeyNormalizer(), new ParseSlots(8, Duration.ofSeconds(2)), new InMemoryHostQueueBoard());

		LibraryPage page = checked.fetch(BUSAN, SearchQuery.of("사랑"), 1);
		LibraryPage cached = checked.fetch(BUSAN, SearchQuery.of("사랑"), 1);

		assertThat(page.items()).extracting(LibraryPage.Item::detailUrl)
			.containsExactly("https://library.busan.go.kr/elib/module/elib/book/view.do?book_idx=1", null);
		assertThat(cached.cached()).isTrue();
		assertThat(cached.items().get(1).detailUrl()).isNull();
		verify(client, times(1)).fetch(any(), any(), any());
	}

	@Test
	void secondKyoboPageHasRanks51To100() { // AC-15
		given(client.fetch(any(), any(), any())).willReturn(ok(html("kyobo-v1/love-p2.html")));

		LibraryPage page = fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 2);

		assertThat(page.pageSize()).isEqualTo(50);
		assertThat(page.totalCount()).isEqualTo(103);
		assertThat(page.hasMore()).isTrue();
		assertThat(page.items()).extracting(LibraryPage.Item::rank).startsWith(51).endsWith(100);
		assertThat(page.librarySearchUrl()).contains("pageIndex=2");
		assertThat(page.items()).allSatisfy(item -> assertThat(item.detailUrl())
			.startsWith("https://www.ghlib.go.kr:446/elibrary-front/content/contentView.ink?cttsDvsnCode="));
	}

	@Test
	void yes24DetailUrlIsTheBookPage() {
		given(client.fetch(any(), any(), any())).willReturn(ok(html("yes24-v2/veg-p1.html")));

		LibraryPage page = fetcher.fetch(GANGSEO, SearchQuery.of("채식주의자"), 1);

		assertThat(page.items()).extracting(LibraryPage.Item::detailUrl)
			.containsExactly("https://ebook.gangseo.seoul.kr/ebook/detail/?goods_id=29090201");
	}

	@Test
	void computesAvailableAndLoanRate() {
		given(client.fetch(any(), any(), any())).willReturn(ok(html("yes24-v2/love-p1.html")));

		LibraryPage page = fetcher.fetch(GANGSEO, SearchQuery.of("사랑"), 1);

		assertThat(page.pageSize()).isEqualTo(10);
		assertThat(page.items()).allSatisfy(item -> assertThat(item.available()).isEqualTo(item.holdings() - item.loaned()));
		long holdings = page.items().stream().mapToLong(LibraryPage.Item::holdings).sum();
		long loaned = page.items().stream().mapToLong(LibraryPage.Item::loaned).sum();
		assertThat(page.loanRate()).isEqualTo((double) loaned / holdings);
	}

	@Test
	void emptyResultHasNullLoanRateAndNoMore() {
		given(client.fetch(any(), any(), any())).willReturn(ok(html("kyobo-v1/none.html")));

		LibraryPage page = fetcher.fetch(GOHEUNG, SearchQuery.of("없는책제목"), 1);

		assertThat(page.items()).isEmpty();
		assertThat(page.loanRate()).isNull();
		assertThat(page.hasMore()).isFalse();
	}

	@Test
	void secondRequestIsServedFromCache() {
		given(client.fetch(any(), any(), any())).willReturn(ok(html("kyobo-v1/veg-p1.html")));

		LibraryPage first = fetcher.fetch(GOHEUNG, SearchQuery.of("채식주의자"), 1);
		LibraryPage second = fetcher.fetch(GOHEUNG, SearchQuery.of("  채식주의자 "), 1);

		assertThat(first.cached()).isFalse();
		assertThat(second.cached()).isTrue();
		assertThat(second.fetchedAt()).isEqualTo(first.fetchedAt());
		verify(client, times(1)).fetch(any(), any(), any());
	}

	@Test
	void sameTextInAnotherFieldIsNotSharedInTheCache() { // AC-6
		given(client.fetch(any(), any(), any())).willReturn(ok(html("kyobo-v1/veg-p1.html")));

		LibraryPage title = fetcher.fetch(GOHEUNG, SearchQuery.of("김달"), 1);
		LibraryPage author = fetcher.fetch(GOHEUNG, SearchQuery.of("김달", SearchField.AUTHOR), 1);
		LibraryPage authorAgain = fetcher.fetch(GOHEUNG, SearchQuery.of("김달", SearchField.AUTHOR), 1);

		assertThat(title.cached()).isFalse();
		assertThat(author.cached()).isFalse();
		assertThat(authorAgain.cached()).isTrue();
		ArgumentCaptor<URI> uris = ArgumentCaptor.forClass(URI.class);
		verify(client, times(2)).fetch(uris.capture(), any(), any());
		assertThat(uris.getAllValues()).extracting(URI::getRawQuery)
			.satisfiesExactly(q -> assertThat(q).startsWith("schClst=ctts&"), q -> assertThat(q).startsWith("schClst=autr&"));
	}

	@Test
	void portalAuthorPageOpensTheSearchPageInTheLibraryButDetailsKeepTheFetchedFolder() {
		given(client.fetch(any(), any(), any())).willReturn(ok(html("elib-portal-v1/busan-table-author-p1.html")));

		LibraryPage page = fetcher.fetch(BUSAN, SearchQuery.of("김달", SearchField.AUTHOR), 1);

		assertThat(page.query()).isEqualTo("김달");
		assertThat(page.totalCount()).isEqualTo(3);
		assertThat(page.librarySearchUrl()).isEqualTo(
				"https://library.busan.go.kr/elib/module/elib/search/index.do?menu_idx=10&search_text=%EA%B9%80%EB%8B%AC");
		assertThat(page.items().get(1).detailUrl()).isEqualTo(
				"https://library.busan.go.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=19745&type=EBK");
	}

	@Test
	void slowLibraryTimesOutButStillFillsTheCache() throws Exception {
		given(client.fetch(any(), any(), any())).willAnswer(invocation -> {
			Thread.sleep(DEADLINE.plusMillis(300));
			return ok(html("kyobo-v1/veg-p1.html"));
		});

		assertThatThrownBy(() -> fetcher.fetch(GOHEUNG, SearchQuery.of("채식주의자"), 1))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));

		Thread.sleep(500);
		assertThat(fetcher.fetch(GOHEUNG, SearchQuery.of("채식주의자"), 1).cached()).isTrue();
	}

	@Test
	void upstreamErrorsPassThrough() {
		given(client.fetch(any(), any(), any())).willThrow(UpstreamException.error("status 500"));

		assertThatThrownBy(() -> fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.ERROR));
	}

	@Test
	void unparseablePageIsAnUpstreamError() {
		given(client.fetch(any(), any(), any())).willReturn(ok("<html>login required</html>"));

		assertThatThrownBy(() -> fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.ERROR));
	}

	@Test
	void nonSuccessStatusIsAnUpstreamError() { // 3xx too: redirects are not followed
		given(client.fetch(any(), any(), any()))
			.willReturn(new SiteResponse(302, null, "https://elsewhere.test/", null, "direct"));

		assertThatThrownBy(() -> fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 1))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.ERROR));
	}

	@Test
	void throttleKeyIsTheHostNameAndLogsKeepThePort() {
		assertThat(HostThrottle.key(URI.create("https://EBOOK.jbe.go.kr:10112/x"))).isEqualTo("ebook.jbe.go.kr");
	}

	private static SiteResponse ok(String html) {
		return new SiteResponse(200, "text/html;charset=UTF-8", null, html.getBytes(StandardCharsets.UTF_8), "direct");
	}

	@Test
	void hostKeyIncludesPort() {
		assertThat(HostThrottle.hostPort(URI.create("https://www.ghlib.go.kr:446/x"))).isEqualTo("www.ghlib.go.kr:446");
		assertThat(HostThrottle.hostPort(URI.create("https://ebook.gangseo.seoul.kr/x")))
			.isEqualTo("ebook.gangseo.seoul.kr:443");
	}

}
