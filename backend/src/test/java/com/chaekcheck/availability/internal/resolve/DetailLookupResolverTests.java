package com.chaekcheck.availability.internal.resolve;

import static com.chaekcheck.availability.internal.adapter.AdapterFixtures.bytes;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ElibPortalV1Adapter;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.InMemoryHostThrottle;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.SiteResponse;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Decision;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;
import com.github.benmanes.caffeine.cache.Ticker;

/** Detail pages from the Gyeongbuk portal (fixtures), through a fake {@link SiteFetcher}. */
class DetailLookupResolverTests {

	static final SearchTarget GB = TestTargets.target(12, PlatformType.ELIB_PORTAL_V1, "https://www.gbelib.kr/elib");

	SiteFetcher siteFetcher = mock(SiteFetcher.class);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	DetailLookupResolver resolver = new DetailLookupResolver(List.of(new ElibPortalV1Adapter()),
			new CaffeineDetailVerdictCache(Duration.ofMinutes(3), 100, executor, Ticker.systemTicker()),
			new InMemoryHostThrottle(1, Duration.ZERO, Clock.systemUTC()), siteFetcher, Clock.systemUTC(),
			new com.chaekcheck.availability.internal.fetch.ParseSlots(8, Duration.ofSeconds(2)));

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void reservableOnTheDetailPage() { // AC-15: 신 퇴마록 신세편 1
		given(siteFetcher.fetch(any(), any())).willReturn(page("elib-portal-v1/detail-reservable.html"));

		assertThat(resolver.lookup(GB, row(63941), inSeconds(5)).join())
			.isEqualTo(new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL));
	}

	@Test
	void availableOnTheDetailPageAndCachedForTheNextSearch() {
		given(siteFetcher.fetch(any(), any())).willReturn(page("elib-portal-v1/detail-available.html"));

		resolver.lookup(GB, row(36476), inSeconds(5)).join();
		Decision second = resolver.lookup(GB, row(36476), inSeconds(5)).join();

		assertThat(second).isEqualTo(new Decision(Availability.AVAILABLE, AvailabilityBasis.SITE_DETAIL));
		verify(siteFetcher, times(1)).fetch(any(), any());
	}

	@Test
	void unknownValueFailureAndTimeoutAreUnknown() {
		Decision unknown = new Decision(Availability.UNKNOWN, AvailabilityBasis.SITE_DETAIL);
		given(siteFetcher.fetch(any(), any())).willReturn(page("elib-portal-v1/detail-unknown.html"));
		assertThat(resolver.lookup(GB, row(1), inSeconds(5)).join()).isEqualTo(unknown);

		willThrow(UpstreamException.error("403")).given(siteFetcher).fetch(any(), any());
		assertThat(resolver.lookup(GB, row(2), inSeconds(5)).join()).isEqualTo(unknown);

		willAnswer(invocation -> {
			Thread.sleep(2000);
			return page("elib-portal-v1/detail-available.html");
		}).given(siteFetcher).fetch(any(), any());
		long start = System.nanoTime();
		assertThat(resolver.lookup(GB, row(3), Instant.now().plusMillis(200)).join()).isEqualTo(unknown);
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofMillis(1500));
	}

	@Test
	void rowWithDetailLinkOffTheLibrarysHostIsUnknownWithoutARequest() { // cycle 20, AC-18 server mode
		LibraryPage.Item offHost = new LibraryPage.Item(1, "t", null, null, null, null, 3, 0, null, "t", null, null,
				"https://evil.test/elib/module/elib/book/view.do?book_idx=1", SiteVerdict.BLANK);
		LibraryPage.Item notHttp = new LibraryPage.Item(2, "t", null, null, null, null, 3, 0, null, "t", null, null,
				"javascript:alert(1)", SiteVerdict.BLANK);

		assertThat(resolver.lookup(GB, offHost, inSeconds(5)).join().availability()).isEqualTo(Availability.UNKNOWN);
		assertThat(resolver.lookup(GB, offHost, inSeconds(5)).join().availability()).isEqualTo(Availability.UNKNOWN);
		assertThat(resolver.lookup(GB, notHttp, inSeconds(5)).join().availability()).isEqualTo(Availability.UNKNOWN);
		verify(siteFetcher, never()).fetch(any(), any());
	}

	@Test
	void rowWithoutDetailLinkIsUnknownWithoutARequest() {
		LibraryPage.Item noLink = new LibraryPage.Item(1, "t", null, null, null, null, 3, 0, null, "t", null, null,
				null, SiteVerdict.BLANK);

		assertThat(resolver.lookup(GB, noLink, inSeconds(5)).join().availability()).isEqualTo(Availability.UNKNOWN);
		verify(siteFetcher, never()).fetch(any(), any());
	}

	private static LibraryPage.Item row(int bookIdx) {
		return new LibraryPage.Item(1, "t", null, null, null, null, 5, 0, null, "t", null, null,
				"https://www.gbelib.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=" + bookIdx + "&type=EBK",
				SiteVerdict.BLANK);
	}

	private static SiteResponse page(String fixture) {
		return new SiteResponse(200, "text/html; charset=UTF-8", null, bytes(fixture), "direct");
	}

	private static Instant inSeconds(int seconds) {
		return Instant.now().plusSeconds(seconds);
	}

}
