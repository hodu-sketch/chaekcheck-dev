package com.chaekcheck.availability.internal.resolve;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.fetch.DetailUrls;
import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.availability.internal.fetch.ParseSlots;
import com.chaekcheck.availability.internal.fetch.SiteFetcher;
import com.chaekcheck.availability.internal.fetch.SiteResponse;
import com.chaekcheck.availability.internal.resolve.DetailVerdictCache.DetailPage;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Loads a book's detail page (ADR-0029) through the same 3-minute cache, host throttle and {@link SiteFetcher} as
 * search pages (호출 예의 [확정]). The one place detail pages are requested: the portal's blank-verdict lookup and the
 * holding stream both use it.
 */
public class DetailPageFetcher {

	private static final Logger log = LoggerFactory.getLogger(DetailPageFetcher.class);

	private final Map<PlatformType, PlatformAdapter> adapters = new EnumMap<>(PlatformType.class);

	private final DetailVerdictCache cache;

	private final HostThrottle throttle;

	private final SiteFetcher siteFetcher;

	private final Clock clock;

	private final ParseSlots parseSlots;

	/** @param parseSlots the same slots as search pages (cycle 20 [안: 같은 자리]) */
	public DetailPageFetcher(List<PlatformAdapter> adapters, DetailVerdictCache cache, HostThrottle throttle,
			SiteFetcher siteFetcher, Clock clock, ParseSlots parseSlots) {
		adapters.forEach(adapter -> this.adapters.put(adapter.platform(), adapter));
		this.cache = cache;
		this.throttle = throttle;
		this.siteFetcher = siteFetcher;
		this.clock = clock;
		this.parseSlots = parseSlots;
	}

	/**
	 * The detail page, from the cache or the site. Completes exceptionally with an {@link UpstreamException} on a
	 * failure (not cached).
	 * @param detailUrl must already have passed {@link #checkedUri}
	 */
	public CompletableFuture<DetailPage> fetch(SearchTarget target, URI detailUrl, Instant deadline) {
		PlatformAdapter adapter = adapters.get(target.platformType());
		if (adapter == null) {
			return CompletableFuture.failedFuture(UpstreamException.error("no adapter for " + target.platformType()));
		}
		return cache.get(new DetailVerdictCache.DetailKey(target.id(), detailUrl.toString()),
				() -> load(adapter, detailUrl, deadline));
	}

	/**
	 * The detail URL when it may be requested [안]: http(s), and the same host name as the library's site root
	 * ({@link DetailUrls#checked}). Null otherwise: then no request is sent and that row fails on its own.
	 */
	public static URI checkedUri(String detailUrl, String baseUrl) {
		return DetailUrls.checked(detailUrl, baseUrl);
	}

	private DetailPage load(PlatformAdapter adapter, URI detailUrl, Instant deadline) {
		// same host, so the throttle key does not change (Bookcube reads the book's XML instead of its page)
		URI uri = adapter.detailFetchUri(detailUrl);
		SiteResponse response = throttle.run(uri, deadline, () -> siteFetcher.fetch(uri, deadline));
		if (!response.isSuccess()) {
			throw UpstreamException.error("detail status " + response.status() + " from " + HostThrottle.hostPort(uri));
		}
		DetailPage page = new DetailPage(
				parseSlots.parse(() -> adapter.parseDetail(response.body(), response.declaredCharset())),
				clock.instant());
		log.info("detail page host={} path={} detail={} via={}", HostThrottle.hostPort(uri), uri.getRawPath(),
				page.detail(), response.via());
		return page;
	}

}
