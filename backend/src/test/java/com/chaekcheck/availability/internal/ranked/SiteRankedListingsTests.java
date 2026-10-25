package com.chaekcheck.availability.internal.ranked;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.RankedListings.Failed;
import com.chaekcheck.availability.RankedListings.Page;
import com.chaekcheck.availability.RankedListings.Result;
import com.chaekcheck.availability.RankedListings.Row;
import com.chaekcheck.availability.RankedListings.Sort;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.adapter.KyoboV1Adapter;
import com.chaekcheck.availability.internal.adapter.Yes24V1Adapter;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.chaekcheck.availability.internal.egress.TestLanes;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.SiteResponse;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Popular-order pages (cycle 23 step 3): URLs, the search parsers on the pages, item keys, no request to a KYOBO
 * subscription host or an unsupported screen.
 */
class SiteRankedListingsTests {

	final Clock clock = Clock.fixed(Instant.parse("2026-10-25T19:00:00Z"), ZoneOffset.UTC);

	final LibraryCatalog libraries = mock(LibraryCatalog.class);

	final List<URI> requested = new ArrayList<>();

	SiteResponse answer;

	SiteRankedListings ranked;

	@BeforeEach
	void setUp() {
		SiteFetcher fetcher = (URI uri, Instant deadline, EgressLane lane) -> {
			requested.add(uri);
			if (answer == null) {
				throw UpstreamException.timeout("no answer");
			}
			return answer;
		};
		ranked = new SiteRankedListings(libraries,
				List.of(new KyoboV1Adapter(AdapterFixtures.PROPERTIES), new Yes24V1Adapter(AdapterFixtures.PROPERTIES)),
				TestLanes.direct(fetcher, clock), new ParseSlots(8, Duration.ofSeconds(2)),
				new RankedListingsProperties(80, 50, Duration.ofSeconds(20)), ".dkyobobook.co.kr", clock);
		target(31, PlatformType.KYOBO_V1, "https://www.ghlib.go.kr:446");
		target(263, PlatformType.YES24_V1, "http://ebook.example.go.kr:8095");
		target(40, PlatformType.KYOBO_SUBSCRIPTION_V1, "https://x.dkyobobook.co.kr");
		target(41, PlatformType.KYOBO_V1, "https://elib.dkyobobook.co.kr");
		target(42, PlatformType.YES24_V2, "https://y.example");
	}

	@Test
	void kyoboContentListInLoanOrder() {
		answer = new SiteResponse(200, "text/html;charset=UTF-8", null, AdapterFixtures.bytes("kyobo-v1/love-p1.html"),
				"direct");

		Result result = ranked.fetch(31, Sort.LOANS, 2);

		assertThat(requested).containsExactly(URI.create("https://www.ghlib.go.kr:446/elibrary-front/content/"
				+ "contentList.ink?cttsDvsnCode=001&contentAll=Y&ctgrId=&orderByKey=loanNmvl&pageIndex=2&recordCount=80"));
		Page page = (Page) result;
		assertThat(page.pageSize()).isEqualTo(80);
		assertThat(page.rows()).isNotEmpty();
		Row first = page.rows().getFirst();
		// ranks go on from the previous page; the item key is fnContentClick's brcd
		assertThat(first.rank()).isEqualTo(81);
		assertThat(first.title()).isEqualTo("사랑에 빠지지 말 것 사랑을 할 것");
		assertThat(first.itemKey()).isEqualTo("4801191369664");
		assertThat(page.rows()).allSatisfy(row -> {
			assertThat(row.loansNow()).isNotNull();
			assertThat(row.reservations()).isNotNull();
			assertThat(row.holdings()).isNotNull();
		});
	}

	@Test
	void yes24V1LendingOrder() {
		answer = new SiteResponse(200, "text/html", null, AdapterFixtures.bytes("yes24-v1/love-p1.html"), "direct");

		Page page = (Page) ranked.fetch(263, Sort.LOANS, 1);

		assertThat(requested).containsExactly(URI.create(
				"http://ebook.example.go.kr:8095/search/?src_key=&srch_order=title&sort=lending&view=50&page_num=1"));
		assertThat(page.pageSize()).isEqualTo(50);
		assertThat(page.rows().getFirst().rank()).isEqualTo(1);
		assertThat(page.rows().getFirst().itemKey()).isEqualTo("118074092");
		assertThat(page.rows()).allSatisfy(row -> assertThat(row.loansNow()).isNotNull());
	}

	@Test
	void kyoboSubscriptionHostsAreNeverRequested() {
		assertThat(ranked.fetch(41, Sort.LOANS, 1)).isEqualTo(new Failed("blocked_host",
				"host elib.dkyobobook.co.kr is not requested"));
		assertThat(((Failed) ranked.fetch(40, Sort.LOANS, 1)).reason()).isEqualTo("unsupported_screen");
		assertThat(requested).isEmpty();
		assertThat(ranked.blocked("A.DKYOBOBOOK.CO.KR")).isTrue();
		assertThat(ranked.blocked("dkyobobook.co.kr")).isTrue();
		assertThat(ranked.blocked("notdkyobobook.co.kr")).isFalse();
	}

	@Test
	void otherScreensAndUnknownLibrariesFailWithoutARequest() {
		assertThat(((Failed) ranked.fetch(42, Sort.LOANS, 1)).reason()).isEqualTo("unsupported_screen");
		assertThat(((Failed) ranked.fetch(999, Sort.LOANS, 1)).reason()).isEqualTo("unknown_library");
		assertThat(requested).isEmpty();
	}

	@Test
	void failuresAreReturnedNotThrown() {
		assertThat(((Failed) ranked.fetch(31, Sort.LOANS, 1)).reason()).isEqualTo("upstream_timeout");
		answer = new SiteResponse(503, null, null, new byte[0], "direct");
		assertThat(((Failed) ranked.fetch(31, Sort.LOANS, 1)).reason()).isEqualTo("upstream_error");
		answer = new SiteResponse(200, "text/html", null, AdapterFixtures.utf8("<html><body>점검 중</body></html>"),
				"direct");
		assertThat(((Failed) ranked.fetch(31, Sort.LOANS, 1)).reason()).isEqualTo("parse_error");
	}

	@Test
	void itemKeysFromBookPages() {
		assertThat(SiteRankedListings.itemKey("/elibrary-front/content/contentView.ink?cttsDvsnCode=001"
				+ "&brcd=4801191369664&ctgrId=050503&sntnAuthCode=1121192101")).isEqualTo("4801191369664");
		assertThat(SiteRankedListings.itemKey("/ebook/detail/?goods_id=117747641")).isEqualTo("117747641");
		assertThat(SiteRankedListings.itemKey("/somewhere")).isNull();
		assertThat(SiteRankedListings.itemKey(null)).isNull();
	}

	private void target(long id, PlatformType platform, String baseUrl) {
		when(libraries.findEnabledTarget(id)).thenReturn(Optional.of(new SearchTarget(id, "도서관" + id, null, platform,
				baseUrl, null, false, null, null, null, false, null, null, null, null, null, false, null, null, null, false,
				null, 1, false)));
	}

}
