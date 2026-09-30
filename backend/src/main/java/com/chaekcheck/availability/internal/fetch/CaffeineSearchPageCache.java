package com.chaekcheck.availability.internal.fetch;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * In-memory cache: expires {@code ttl} after write, at most {@code maxSize} pages. Logs hit/miss (AC-16).
 */
public class CaffeineSearchPageCache implements SearchPageCache {

	private static final Logger log = LoggerFactory.getLogger(CaffeineSearchPageCache.class);

	private final AsyncCache<PageKey, FetchedPage> cache;

	public CaffeineSearchPageCache(Duration ttl, long maxSize, Executor executor, Ticker ticker) {
		// Caffeine drops a future that completes exceptionally, so failures are not cached
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(ttl)
			.maximumSize(maxSize)
			.executor(executor)
			.ticker(ticker)
			.buildAsync();
	}

	@Override
	public CompletableFuture<Lookup> get(PageKey key, Supplier<FetchedPage> loader) {
		AtomicBoolean loaded = new AtomicBoolean();
		CompletableFuture<FetchedPage> future = cache.get(key, (k, executor) -> {
			loaded.set(true);
			return CompletableFuture.supplyAsync(loader, executor);
		});
		if (!loaded.get() && future.isCompletedExceptionally()) {
			// Caffeine removes a failed load asynchronously; do not hand that failure to a new request
			cache.asMap().remove(key, future);
			future = cache.get(key, (k, executor) -> {
				loaded.set(true);
				return CompletableFuture.supplyAsync(loader, executor);
			});
		}
		boolean hit = !loaded.get();
		log.info("search cache {} library={} page={} field={} query=\"{}\"", hit ? "hit" : "miss", key.libraryId(),
				key.page(), key.field(), key.query());
		return future.thenApply(page -> new Lookup(page, hit));
	}

}
