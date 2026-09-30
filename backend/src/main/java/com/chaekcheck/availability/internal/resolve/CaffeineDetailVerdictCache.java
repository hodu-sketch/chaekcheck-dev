package com.chaekcheck.availability.internal.resolve;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;

/** In-memory {@link DetailVerdictCache}: expires {@code ttl} after write, at most {@code maxSize} entries. */
public class CaffeineDetailVerdictCache implements DetailVerdictCache {

	private final AsyncCache<DetailKey, DetailPage> cache;

	public CaffeineDetailVerdictCache(Duration ttl, long maxSize, Executor executor, Ticker ticker) {
		this.cache = Caffeine.newBuilder()
			.expireAfterWrite(ttl)
			.maximumSize(maxSize)
			.executor(executor)
			.ticker(ticker)
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

}
