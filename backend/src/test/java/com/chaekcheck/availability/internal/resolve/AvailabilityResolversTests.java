package com.chaekcheck.availability.internal.resolve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.adapter.ParsedDetail;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Decision;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Resolution;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

class AvailabilityResolversTests {

	static final Instant DEADLINE = Instant.now().plusSeconds(30);

	DetailLookupResolver detailLookup = mock(DetailLookupResolver.class);

	AvailabilityResolvers resolvers = new AvailabilityResolvers(detailLookup);

	@Test
	void resolverIsChosenByScreenKind() {
		assertThat(resolvers.forPlatform(PlatformType.KYOBO_V1)).isInstanceOf(CountResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.YES24_V1)).isInstanceOf(CountResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.YES24_V2)).isInstanceOf(CountResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.GANGNAM_V1)).isInstanceOf(CountResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.KYOBO_SUBSCRIPTION_V1)).isInstanceOf(SubscriptionResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.ELIB_PORTAL_V1)).isInstanceOf(SiteListResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.ALADIN_V1)).isInstanceOf(CountResolver.class);
		assertThat(resolvers.forPlatform(PlatformType.BOOKCUBE_FX)).isInstanceOf(SubscriptionResolver.class);
	}

	/** Cycle 07 (ADR-0056): owned rows on a subscription screen by count, rental rows as subscription. */
	@Test
	void subscriptionScreensDecideOwnedRowsByCount() { // AC-9, AC-23
		LibraryPage.Item rental = new LibraryPage.Item(3, "t3", null, null, null, 0, 0, 0, 0, "t3", null, null, null,
				SiteVerdict.SUBSCRIPTION);

		assertThat(resolve(PlatformType.KYOBO_SUBSCRIPTION_V1, counted(1, 1, 1), noCounts(2, null)).page().items())
			.extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsExactly(tuple(Availability.RESERVABLE, AvailabilityBasis.COUNT),
					tuple(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION));
		assertThat(resolve(PlatformType.BOOKCUBE_FX, counted(1, 5, 2), rental).page().items())
			.extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsExactly(tuple(Availability.AVAILABLE, AvailabilityBasis.COUNT),
					tuple(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION));
	}

	@Test
	void rentalDetailIsASubscription() {
		assertThat(resolvers.decideDetail(PlatformType.BOOKCUBE_FX,
				new ParsedDetail(0, 0, 0, SiteVerdict.SUBSCRIPTION)))
			.isEqualTo(new Decision(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION));
		assertThat(resolvers.decideDetail(PlatformType.ALADIN_V1, ParsedDetail.counted(1, 1, 0)))
			.isEqualTo(new Decision(Availability.RESERVABLE, AvailabilityBasis.COUNT));
	}

	@Test
	void countScreensUseHoldingsMinusLoaned() {
		Resolution resolution = resolve(PlatformType.KYOBO_V1, counted(1, 3, 2), counted(2, 1, 1), noCounts(3, null));

		assertThat(resolution.pending()).isEmpty();
		assertThat(resolution.page().items()).extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsExactly(tuple(Availability.AVAILABLE, AvailabilityBasis.COUNT),
					tuple(Availability.RESERVABLE, AvailabilityBasis.COUNT),
					tuple(Availability.UNKNOWN, AvailabilityBasis.COUNT));
	}

	@Test
	void subscriptionIsAlwaysAvailable() { // AC-16
		Resolution resolution = resolve(PlatformType.KYOBO_SUBSCRIPTION_V1, noCounts(1, null), noCounts(2, null));

		assertThat(resolution.page().items()).extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsOnly(tuple(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION));
	}

	@Test
	void portalListVerdictIsUsedAsIs() { // AC-14
		Resolution resolution = resolve(PlatformType.ELIB_PORTAL_V1, portal(1, SiteVerdict.AVAILABLE, 2),
				portal(2, SiteVerdict.RESERVABLE, 5), portal(3, SiteVerdict.OTHER, 1));

		assertThat(resolution.pending()).isEmpty();
		assertThat(resolution.page().items()).extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsExactly(tuple(Availability.AVAILABLE, AvailabilityBasis.SITE_LIST),
					tuple(Availability.RESERVABLE, AvailabilityBasis.SITE_LIST),
					tuple(Availability.UNKNOWN, AvailabilityBasis.SITE_LIST));
		verify(detailLookup, never()).lookup(any(), any(), any());
	}

	@Test
	void blankVerdictWithNothingOnLoanNeedsNoDetailRequest() { // AC-15
		Resolution resolution = resolve(PlatformType.ELIB_PORTAL_V1, portal(1, SiteVerdict.BLANK, 0));

		assertThat(resolution.page().items().getFirst()).extracting(LibraryPage.Item::availability,
				LibraryPage.Item::availabilityBasis)
			.containsExactly(Availability.AVAILABLE, AvailabilityBasis.LOANED_ZERO);
		verify(detailLookup, never()).lookup(any(), any(), any());
	}

	@Test
	void blankVerdictOnLoanIsPendingUntilTheDetailPageSays() { // AC-15
		CompletableFuture<Decision> detail = new CompletableFuture<>();
		given(detailLookup.lookup(any(), argThat(item -> item.rank() == 2), any())).willReturn(detail);

		Resolution resolution = resolve(PlatformType.ELIB_PORTAL_V1, portal(1, SiteVerdict.BLANK, 0),
				portal(2, SiteVerdict.BLANK, 5), portal(3, SiteVerdict.BLANK, null));

		assertThat(resolution.page().items()).extracting(LibraryPage.Item::availability)
			.containsExactly(Availability.AVAILABLE, Availability.PENDING, Availability.PENDING);
		assertThat(resolution.pending()).extracting(AvailabilityResolver.PendingRow::rank).containsExactly(2, 3);
		detail.complete(new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL));
		assertThat(resolution.pending().getFirst().decision()).isCompletedWithValue(
				new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL));
	}

	@Test
	void completedPageWaitsForPendingRows() {
		given(detailLookup.lookup(any(), any(), any())).willReturn(CompletableFuture
			.completedFuture(new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL)));

		LibraryPage page = resolve(PlatformType.ELIB_PORTAL_V1, portal(1, SiteVerdict.BLANK, 5)).completedPage();

		assertThat(page.items().getFirst()).extracting(LibraryPage.Item::availability, LibraryPage.Item::availabilityBasis)
			.containsExactly(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL);
	}

	private Resolution resolve(PlatformType type, LibraryPage.Item... items) {
		SearchTarget target = TestTargets.target(9, type, "https://lib9.test");
		LibraryPage page = new LibraryPage(9, "사랑", 1, 10, items.length, false, Instant.now(), false, 0, null,
				"https://lib9.test/search", List.of(items));
		return resolvers.forPlatform(type).resolve(target, page, DEADLINE);
	}

	static LibraryPage.Item counted(int rank, int holdings, int loaned) {
		return new LibraryPage.Item(rank, "t" + rank, null, null, null, holdings, loaned, 0, holdings - loaned,
				"t" + rank, null, null, null, null);
	}

	static LibraryPage.Item noCounts(int rank, SiteVerdict verdict) {
		return new LibraryPage.Item(rank, "t" + rank, null, null, null, null, null, null, null, "t" + rank, null, null,
				null, verdict);
	}

	static LibraryPage.Item portal(int rank, SiteVerdict verdict, Integer loaned) {
		return new LibraryPage.Item(rank, "t" + rank, null, null, null, null, loaned, 0, null, "t" + rank, null, null,
				"https://lib9.test/elib/module/elib/book/view.do?book_idx=" + rank, verdict);
	}

}
