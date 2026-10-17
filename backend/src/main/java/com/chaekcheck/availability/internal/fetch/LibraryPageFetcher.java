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
import com.chaekcheck.availability.internal.egress.EgressLanes;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;

/**
 * Fetches one page of one library: cache, then the egress lanes ({@link EgressLanes}: lane choice, per-lane host
 * throttle and the lane's {@link SiteFetcher}, cycle 22), then adapter inside a {@link ParseSlots} slot (cycle 20).
 */
public class LibraryPageFetcher {

	private final Map<PlatformType, PlatformAdapter> adapters = new EnumMap<>(PlatformType.class);

	private final SearchPageCache cache;

	private final EgressLanes lanes;

	private final Duration requestDeadline;

	private final Clock clock;

	private final BookKeyNormalizer titleKeys;

	private final ParseSlots parseSlots;

	private final HostQueueBoard queueBoard;

	public LibraryPageFetcher(List<PlatformAdapter> adapters, SearchPageCache cache, EgressLanes lanes,
			Duration requestDeadline, Clock clock, BookKeyNormalizer titleKeys, ParseSlots parseSlots,
			HostQueueBoard queueBoard) {
		adapters.forEach(adapter -> this.adapters.put(adapter.platform(), adapter));
		this.cache = cache;
		this.lanes = lanes;
		this.requestDeadline = requestDeadline;
		this.clock = clock;
		this.titleKeys = titleKeys;
		this.parseSlots = parseSlots;
		this.queueBoard = queueBoard;
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
		return cache.get(key, () -> load(target.id(), adapter, uri, deadline))
			.thenApply(lookup -> toPage(target, query, page, adapter, lookup))
			.exceptionallyCompose(ex -> CompletableFuture.failedFuture(unwrap(ex)));
	}

	private FetchedPage load(long libraryId, PlatformAdapter adapter, URI uri, Instant deadline) {
		SiteResponse response = routed(queueBoard, lanes, libraryId, uri, deadline, clock);
		if (!response.isSuccess()) {
			// 3xx included: redirects are not followed (cycle 01 rule)
			throw UpstreamException.error("status " + response.status() + " from " + HostThrottle.hostPort(uri)
					+ " via " + response.via());
		}
		// adapters decode the bytes themselves: EUC-KR sites send no charset in Content-Type. The parse waits for a
		// slot (cycle 20); without one in time this library fails with parse_busy and nothing is cached
		return new FetchedPage(parseSlots.parse(() -> adapter.parse(response.body(), response.declaredCharset())),
				uri, clock.instant());
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
			// cycle 20 (AC-18): a detail link off the library's host is dropped here, so it is neither shown nor
			// requested, on the cache path too (the cached page already carries null)
			URI detailUri = p.detailPath() == null ? null
					: DetailUrls.checked(adapter.detailUri(searchUri, p.detailPath()).toString(), target.baseUrl());
			items.add(new LibraryPage.Item((page - 1) * pageSize + i + 1, p.title(), p.author(), p.publisher(),
					p.supplier(), p.holdings(), p.loaned(), p.reservations(), available, titleKeys.key(p.title()),
					null, null, detailUri == null ? null : detailUri.toString(), p.siteVerdict()));
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

	/**
	 * The lane request wrapped for the {@link HostQueueBoard} (cycle 20, overall review #3): the wait begins before
	 * the lanes look for a free slot, ends when a lane starts sending, and again in {@code finally}; a TIMEOUT
	 * thrown while the wait is still open is a throttle timeout, recorded for that host. A host whose every lane is
	 * paused fails at once ({@code no_lane_for_host}), so it is not queued. Shared with {@code DetailPageFetcher}.
	 */
	public static SiteResponse routed(HostQueueBoard board, EgressLanes lanes, long libraryId, URI uri,
			Instant deadline, Clock clock) {
		String hostKey = SiteHostNames.hostOrNull(uri);
		if (hostKey == null) {
			// no readable host: the lanes fail this request (ERROR), nothing to queue
			return lanes.fetch(uri, deadline);
		}
		HostQueueBoard.Wait wait = board.begin(HostQueueBoard.DEFAULT_LANE, hostKey, libraryId, clock.instant());
		try {
			return lanes.fetch(uri, deadline, wait::end);
		}
		catch (UpstreamException ex) {
			if (ex.kind() == UpstreamException.Kind.TIMEOUT && !wait.ended()) {
				board.timedOut(HostQueueBoard.DEFAULT_LANE, hostKey, clock.instant());
			}
			throw ex;
		}
		finally {
			wait.end();
		}
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
