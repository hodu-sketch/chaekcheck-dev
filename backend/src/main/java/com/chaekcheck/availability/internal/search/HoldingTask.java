package com.chaekcheck.availability.internal.search;

import java.net.URI;

import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * One holding's detail page (cycle 04 holding stream). Subscription holdings are never requested [확정]; a detail URL
 * that is not http(s) on the library's own host is not requested either and fails on its own [안].
 * @param detailUri the checked URL, or null when it may not be requested
 */
public record HoldingTask(SearchTarget target, HoldingRef holding, URI detailUri) implements LookupTask {

	public static HoldingTask of(SearchTarget target, HoldingRef holding) {
		return new HoldingTask(target, holding, DetailPageFetcher.checkedUri(holding.detailUrl(), target.baseUrl()));
	}

	public boolean subscription() {
		return target.platformType() == PlatformType.KYOBO_SUBSCRIPTION_V1;
	}

	@Override
	public String hostKey() {
		return subscription() || detailUri == null ? null : HostThrottle.key(detailUri);
	}

}
