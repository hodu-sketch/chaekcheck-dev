package com.chaekcheck.availability.internal.resolve;

import java.util.EnumMap;
import java.util.Map;

import com.chaekcheck.availability.internal.adapter.ParsedDetail;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.PlatformType;

/**
 * The one place that maps a screen kind to how its rows are decided (design-delta "판정 방식 인터페이스").
 */
public class AvailabilityResolvers {

	private final Map<PlatformType, AvailabilityResolver> byPlatform = new EnumMap<>(PlatformType.class);

	public AvailabilityResolvers(DetailLookupResolver detailLookup) {
		CountResolver count = new CountResolver();
		for (PlatformType type : PlatformType.values()) {
			byPlatform.put(type, switch (type) {
				case KYOBO_V1, YES24_V1, YES24_V2, YES24_V3, GANGNAM_V1, BOOKCUBE_FX, ALADIN_V1 -> count;
				case KYOBO_SUBSCRIPTION_V1 -> new SubscriptionResolver();
				case ELIB_PORTAL_V1 -> new SiteListResolver(detailLookup);
			});
		}
	}

	public AvailabilityResolver forPlatform(PlatformType type) {
		return byPlatform.get(type);
	}

	/**
	 * One holding row from its detail page (cycle 04 holding stream), with the same rules as the list resolvers:
	 * counts give AVAILABLE when holdings - loaned >= 1, else RESERVABLE (COUNT); counts replaced by a notice are
	 * UNKNOWN; the portal's "대출 가능" / "예약 가능" are AVAILABLE / RESERVABLE (SITE_DETAIL), anything else UNKNOWN.
	 * Subscription rows never read a page: {@link #subscription()}.
	 */
	public AvailabilityResolver.Decision decideDetail(PlatformType type, ParsedDetail detail) {
		if (type == PlatformType.ELIB_PORTAL_V1) {
			SiteVerdict verdict = detail.siteVerdict();
			Availability availability = verdict == SiteVerdict.AVAILABLE ? Availability.AVAILABLE
					: verdict == SiteVerdict.RESERVABLE ? Availability.RESERVABLE : Availability.UNKNOWN;
			return new AvailabilityResolver.Decision(availability, AvailabilityBasis.SITE_DETAIL);
		}
		if (detail.holdings() == null || detail.loaned() == null) {
			return new AvailabilityResolver.Decision(Availability.UNKNOWN, AvailabilityBasis.COUNT);
		}
		return new AvailabilityResolver.Decision(
				detail.holdings() - detail.loaned() >= 1 ? Availability.AVAILABLE : Availability.RESERVABLE,
				AvailabilityBasis.COUNT);
	}

	/** Subscription: anyone can borrow at once (same as {@link SubscriptionResolver}). */
	public AvailabilityResolver.Decision subscription() {
		return new AvailabilityResolver.Decision(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION);
	}

}
