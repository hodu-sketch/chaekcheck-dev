package com.chaekcheck.availability.internal.fetch;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Cache of fetched pages (ADR-0006). Behind an interface so it can move to Redis later.
 * Concurrent requests for the same key share one load; failed loads are not cached.
 * A load keeps running even when every caller has stopped waiting, and its result is cached.
 */
public interface SearchPageCache {

	CompletableFuture<Lookup> get(PageKey key, Supplier<FetchedPage> loader);

	/** @param hit true when the page came from the cache (or from a load another request started) */
	record Lookup(FetchedPage page, boolean hit) {
	}

}
