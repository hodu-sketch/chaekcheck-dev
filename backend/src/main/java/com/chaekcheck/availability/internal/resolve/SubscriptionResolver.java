package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.List;

import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.SearchTarget;

/**
 * Subscription sites: any number of users can borrow a book at once, so every row is AVAILABLE. The monthly
 * budget is not visible on the search page; the web shows a notice instead.
 */
public class SubscriptionResolver implements AvailabilityResolver {

	@Override
	public Resolution resolve(SearchTarget target, LibraryPage page, Instant detailDeadline) {
		return new Resolution(page.withItems(items -> items.stream()
			.map(item -> item.withAvailability(Availability.AVAILABLE, AvailabilityBasis.SUBSCRIPTION))
			.toList()), List.of());
	}

}
