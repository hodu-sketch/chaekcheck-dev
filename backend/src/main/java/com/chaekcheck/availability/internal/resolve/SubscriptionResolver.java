package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.List;

import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.SearchTarget;

/**
 * Subscription sites: any number of users can borrow a book at once, so a subscription row is AVAILABLE. The
 * monthly budget is not visible on the search page; the web shows a notice instead. Since cycle 07 (ADR-0056) a
 * row that shows counts (an owned title on the KYOBO subscription screen, a Bookcube owned title) is decided by its
 * counts, and a Bookcube rental row ({@link SiteVerdict#SUBSCRIPTION}) is a subscription.
 */
public class SubscriptionResolver implements AvailabilityResolver {

	private final CountResolver count;

	public SubscriptionResolver(CountResolver count) {
		this.count = count;
	}

	@Override
	public Resolution resolve(SearchTarget target, LibraryPage page, Instant detailDeadline) {
		return new Resolution(page.withItems(items -> items.stream().map(item -> {
			if (item.holdings() != null && item.siteVerdict() != SiteVerdict.SUBSCRIPTION) {
				return count.decide(item);
			}
			return item.withAvailability(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION);
		}).toList()), List.of());
	}

}
