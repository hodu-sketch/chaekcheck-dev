package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ParsedItem;
import com.chaekcheck.availability.internal.adapter.ParsedPage;
import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Fetches one page of one library: cache, then host throttle, then {@link SiteFetcher}, then adapter.
 */
public class LibraryPageFetcher {

	private final Map<PlatformType, PlatformAdapter> adapters = new EnumMap<>(PlatformType.class);

	private final SearchPageCache cache;

	private final HostThrottle throttle;

	private final SiteFetcher siteFetcher;

	private final Duration requestDeadline;

	private final Clock clock;

	private final BookKeyNormalizer titleKeys;

	public LibraryPageFetcher(List<PlatformAdapter> adapters, SearchPageCache cache, HostThrottle throttle,
			SiteFetcher siteFetcher, Duration requestDeadline, Clock clock, BookKeyNormalizer titleKeys) {
		adapters.forEach(adapter -> this.adapters.put(adapter.platform(), adapter));
		this.cache = cache;
		this.throttle = throttle;
		this.siteFetcher = siteFetcher;
		this.requestDeadline = requestDeadline;
		this.clock = clock;
		this.titleKeys = titleKeys;
	}

	/**
	 * Blocks until the page is ready or {@code requestDeadline} (8s, throttle wait included) has passed.
	 * @throws UpstreamException TIMEOUT after the deadline (the load keeps running and fills the cache), ERROR otherwise
	 */
	public LibraryPage fetch(SearchTarget target, SearchQuery query, int page) {
		CompletableFuture<LibraryPage> future = fetchAsync(target, query, page);
		try {
			return future.get(requestDeadline.toNanos(), TimeUnit.NANOSECONDS);
		}
		catch (TimeoutException ex) {
			throw UpstreamException.timeout("no response within " + requestDeadline.toSeconds() + " seconds");
		}
		catch (ExecutionException ex) {
			throw unwrap(ex.getCause());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new UpstreamException(UpstreamException.Kind.ERROR, "interrupted", ex);
		}
	}

	/** Starts the fetch without blocking; the load must finish by now + {@code requestDeadline}. */
	public CompletableFuture<LibraryPage> fetchAsync(SearchTarget target, SearchQuery query, int page) {
		return fetchAsync(target, query, page, clock.instant().plus(requestDeadline));
	}

	/**
	 * Starts the fetch without blocking. Throttle waits count toward {@code deadline}. The future fails with
	 * {@link UpstreamException}; the caller enforces its own deadline on the future (a load keeps running and fills
	 * the cache).
	 */
	public CompletableFuture<LibraryPage> fetchAsync(SearchTarget target, SearchQuery query, int page,
			Instant deadline) {
		PlatformAdapter adapter = adapters.get(target.platformType());
		if (adapter == null) {
			return CompletableFuture.failedFuture(
					UpstreamException.error("no adapter for platform type " + target.platformType()));
		}
		URI uri = adapter.searchUri(target.baseUrl(), query, page);
		PageKey key = new PageKey(target.id(), query.field(), query.value(), page);
		return cache.get(key, () -> load(adapter, uri, deadline))
			.thenApply(lookup -> toPage(target, query, page, adapter, lookup))
			.exceptionallyCompose(ex -> CompletableFuture.failedFuture(unwrap(ex)));
	}

	private FetchedPage load(PlatformAdapter adapter, URI uri, Instant deadline) {
		SiteResponse response = throttle.run(uri, deadline, () -> siteFetcher.fetch(uri, deadline));
		if (!response.isSuccess()) {
			// 3xx included: redirects are not followed (cycle 01 rule)
			throw UpstreamException.error("status " + response.status() + " from " + HostThrottle.hostPort(uri)
					+ " via " + response.via());
		}
		// adapters decode the bytes themselves: EUC-KR sites send no charset in Content-Type
		return new FetchedPage(adapter.parse(response.body(), response.declaredCharset()), uri, clock.instant());
	}

	private LibraryPage toPage(SearchTarget target, SearchQuery query, int page, PlatformAdapter adapter,
			SearchPageCache.Lookup lookup) {
		URI searchUri = lookup.page().searchUri();
		int pageSize = adapter.pageSize();
		ParsedPage parsed = lookup.page().parsed();
		List<LibraryPage.Item> items = new ArrayList<>();
		long holdingsTotal = 0;
		long loanedTotal = 0;
		for (int i = 0; i < parsed.items().size(); i++) {
			ParsedItem p = parsed.items().get(i);
			Integer available = p.holdings() == null || p.loaned() == null ? null : p.holdings() - p.loaned();
			items.add(new LibraryPage.Item((page - 1) * pageSize + i + 1, p.title(), p.author(), p.publisher(),
					p.supplier(), p.holdings(), p.loaned(), p.reservations(), available, titleKeys.key(p.title()),
					null, null,
					p.detailPath() == null ? null : adapter.detailUri(searchUri, p.detailPath()).toString(),
					p.siteVerdict()));
			if (p.holdings() != null && p.loaned() != null) {
				holdingsTotal += p.holdings();
				loanedTotal += p.loaned();
			}
		}
		// screens without holdings have no loan rate (design-delta: null for portal and subscription)
		Double loanRate = holdingsTotal == 0 ? null : (double) loanedTotal / holdingsTotal;
		Instant fetchedAt = lookup.page().fetchedAt();
		long age = lookup.hit() ? Math.max(0, Duration.between(fetchedAt, clock.instant()).toSeconds()) : 0;
		return new LibraryPage(target.id(), query.value(), page, pageSize, parsed.totalCount(),
				(long) page * pageSize < parsed.totalCount(), fetchedAt, lookup.hit(), age, loanRate,
				adapter.librarySearchUri(target.baseUrl(), query, searchUri).toString(), items);
	}

	private static UpstreamException unwrap(Throwable ex) {
		Throwable cause = ex;
		while ((cause instanceof CompletionException || cause instanceof ExecutionException)
				&& cause.getCause() != null) {
			cause = cause.getCause();
		}
		return cause instanceof UpstreamException upstream ? upstream
				: new UpstreamException(UpstreamException.Kind.ERROR, "unexpected failure: " + cause, cause);
	}

}
