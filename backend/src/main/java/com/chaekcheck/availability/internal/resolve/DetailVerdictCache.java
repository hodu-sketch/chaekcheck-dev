package com.chaekcheck.availability.internal.resolve;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.chaekcheck.availability.internal.adapter.ParsedDetail;

/**
 * Cache of detail pages as read ({@link ParsedDetail}: counts or the site's verdict), keyed by library and detail
 * URL (3 minutes, like search pages). Used by the portal's blank-verdict lookup and by the holding stream (cycle 04).
 * Behind an interface so it can move to a shared store when the main service runs on several instances
 * (plan-feedback C2-U-1). Concurrent requests for one key share one load; failed loads are not cached.
 */
public interface DetailVerdictCache {

	CompletableFuture<DetailPage> get(DetailKey key, Supplier<DetailPage> loader);

	/** @param detailUrl the book's detail page, which already names the book ({@code book_idx}, {@code brcd}, ...) */
	record DetailKey(long libraryId, String detailUrl) {
	}

	/** @param fetchedAt when the page was read from the site */
	record DetailPage(ParsedDetail detail, Instant fetchedAt) {
	}

}
