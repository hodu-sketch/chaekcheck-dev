package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.List;

import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.SearchTarget;

/**
 * Screens with counts: holdings - loaned >= 1 is AVAILABLE, otherwise RESERVABLE. A row whose counts the site
 * replaced with a notice (Gangnam "예약정원이 마감되었습니다") is UNKNOWN.
 */
public class CountResolver implements AvailabilityResolver {

	@Override
	public Resolution resolve(SearchTarget target, LibraryPage page, Instant detailDeadline) {
		return new Resolution(page.withItems(items -> items.stream()
			.map(item -> item.withAvailability(availability(item), AvailabilityBasis.COUNT))
			.toList()), List.of());
	}

	private static Availability availability(LibraryPage.Item item) {
		if (item.available() == null) {
			return Availability.UNKNOWN;
		}
		return item.available() >= 1 ? Availability.AVAILABLE : Availability.RESERVABLE;
	}

}
