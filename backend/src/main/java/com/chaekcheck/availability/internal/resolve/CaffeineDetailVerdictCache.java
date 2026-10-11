package com.chaekcheck.availability.internal.resolve;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.chaekcheck.availability.internal.CacheStatistics;
import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/** In-memory {@link DetailVerdictCache}: expires {@code ttl} after write, at most {@code maxSize} entries. */
public class CaffeineDetailVerdictCache implements DetailVerdictCache, CacheStatistics {

	private final AsyncCache<DetailKey, DetailPage> cache;

	public CaffeineDetailVerdictCache(Duration ttl, long maxSize, Executor executor, Ticker ticker) {
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(ttl)
			.maximumSize(maxSize)
			.executor(executor)
			.ticker(ticker)
			.recordStats()
			.buildAsync();
	}

	@Override
	public CompletableFuture<DetailPage> get(DetailKey key, Supplier<DetailPage> loader) {
		AtomicBoolean loaded = new AtomicBoolean();
		CompletableFuture<DetailPage> future = cache.get(key, (k, executor) -> {
			loaded.set(true);
			return CompletableFuture.supplyAsync(loader, executor);
		});
		if (!loaded.get() && future.isCompletedExceptionally()) {
			// Caffeine removes a failed load asynchronously; do not hand that failure to a new request
			cache.asMap().remove(key, future);
			future = cache.get(key, (k, executor) -> CompletableFuture.supplyAsync(loader, executor));
		}
		return future;
	}

	@Override
	public String cacheName() {
		return "detail-verdict";
	}

	@Override
	public CacheStats stats() {
		return cache.synchronous().stats();
	}

	@Override
	public long size() {
		return cache.synchronous().estimatedSize();
	}

}
