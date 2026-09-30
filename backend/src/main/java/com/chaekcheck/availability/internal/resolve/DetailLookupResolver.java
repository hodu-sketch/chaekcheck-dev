package com.chaekcheck.availability.internal.resolve;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Decision;
import com.chaekcheck.library.SearchTarget;

/**
 * Reads one row's verdict from the book's detail page, through the same cache (3 min), host throttle and
 * {@link SiteFetcher} as search pages. The future always completes by the deadline it is given: "대출 가능" /
 * "예약 가능" become AVAILABLE / RESERVABLE; a failure, a timeout, a blank or an unknown value becomes UNKNOWN.
 * Only {@link SiteListResolver} uses it; batch code never sees the deadline or the extra requests (ADR-0019).
 */
public class DetailLookupResolver {

	private static final Logger log = LoggerFactory.getLogger(DetailLookupResolver.class);

	private static final Decision UNKNOWN = new Decision(Availability.UNKNOWN, AvailabilityBasis.SITE_DETAIL);

	private final DetailPageFetcher pages;

	private final Clock clock;

	public DetailLookupResolver(DetailPageFetcher pages, Clock clock) {
		this.pages = pages;
		this.clock = clock;
	}

	public DetailLookupResolver(List<PlatformAdapter> adapters, DetailVerdictCache cache, HostThrottle throttle,
			SiteFetcher siteFetcher, Clock clock) {
		this(new DetailPageFetcher(adapters, cache, throttle, siteFetcher, clock), clock);
	}

	public CompletableFuture<Decision> lookup(SearchTarget target, LibraryPage.Item item, Instant deadline) {
		if (item.detailUrl() == null) {
			return CompletableFuture.completedFuture(UNKNOWN);
		}
		URI uri = URI.create(item.detailUrl());
		long left = Math.max(0, Duration.between(clock.instant(), deadline).toMillis());
		return pages.fetch(target, uri, deadline)
			.thenApply(page -> decision(page.detail().siteVerdict()))
			.exceptionally(ex -> {
				log.info("detail lookup failed library={} rank={} url={}: {}", target.id(), item.rank(), uri, ex.toString());
				return UNKNOWN;
			})
			.completeOnTimeout(UNKNOWN, left, TimeUnit.MILLISECONDS);
	}

	private static Decision decision(SiteVerdict verdict) {
		if (verdict == null) {
			return UNKNOWN;
		}
		return switch (verdict) {
			case AVAILABLE -> new Decision(Availability.AVAILABLE, AvailabilityBasis.SITE_DETAIL);
			case RESERVABLE -> new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL);
			case BLANK, OTHER -> UNKNOWN;
		};
	}

}
