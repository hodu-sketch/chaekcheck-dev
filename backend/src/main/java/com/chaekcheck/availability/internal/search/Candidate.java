package com.chaekcheck.availability.internal.search;

import java.net.URI;

import com.chaekcheck.availability.internal.fetch.SiteHostNames;
import com.chaekcheck.library.SearchTarget;

/** One library page a batch may request, in plan order. */
public record Candidate(SearchTarget target, int page) implements LookupTask {

	/**
	 * The throttle's host name. Host names with an underscore are read too (cycle 18). An address without a readable
	 * host keeps its own key (the whole address), so it still takes a slot and its request fails alone as ERROR
	 * instead of breaking the batch.
	 */
	@Override
	public String hostKey() {
		try {
			String host = SiteHostNames.hostOrNull(URI.create(target.baseUrl()));
			return host != null ? host : target.baseUrl();
		}
		catch (IllegalArgumentException ex) {
			return target.baseUrl();
		}
	}

}
