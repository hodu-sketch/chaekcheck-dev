package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.library.SearchTarget;

/**
 * Portal screens: the site's own verdict per row. Row by row, whatever the library's {@code deferred} flag:
 * <ul>
 * <li>"대출 가능" / "예약 가능": AVAILABLE / RESERVABLE (SITE_LIST); another text: UNKNOWN</li>
 * <li>blank and nobody has it on loan: AVAILABLE (LOANED_ZERO), no extra request</li>
 * <li>blank and on loan (or the count is missing): PENDING, decided by {@link DetailLookupResolver}</li>
 * </ul>
 */
public class SiteListResolver implements AvailabilityResolver {

	private final DetailLookupResolver detailLookup;

	public SiteListResolver(DetailLookupResolver detailLookup) {
		this.detailLookup = detailLookup;
	}

	@Override
	public Resolution resolve(SearchTarget target, LibraryPage page, Instant detailDeadline) {
		List<PendingRow> pending = new ArrayList<>();
		List<LibraryPage.Item> items = new ArrayList<>();
		for (LibraryPage.Item item : page.items()) {
			SiteVerdict verdict = item.siteVerdict() == null ? SiteVerdict.BLANK : item.siteVerdict();
			LibraryPage.Item decided = switch (verdict) {
				case AVAILABLE -> item.withAvailability(Availability.AVAILABLE, AvailabilityBasis.SITE_LIST);
				case RESERVABLE -> item.withAvailability(Availability.RESERVABLE, AvailabilityBasis.SITE_LIST);
				case OTHER -> item.withAvailability(Availability.UNKNOWN, AvailabilityBasis.SITE_LIST);
				case BLANK -> {
					if (item.loaned() != null && item.loaned() == 0) {
						yield item.withAvailability(Availability.AVAILABLE, AvailabilityBasis.LOANED_ZERO);
					}
					pending.add(new PendingRow(item.rank(), detailLookup.lookup(target, item, detailDeadline)));
					yield item.withAvailability(Availability.PENDING, AvailabilityBasis.SITE_DETAIL);
				}
			};
			items.add(decided);
		}
		return new Resolution(page.withItems(old -> items), pending);
	}

}
