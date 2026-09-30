package com.chaekcheck.availability.internal.search;

import java.net.URI;

import com.chaekcheck.availability.internal.fetch.HostThrottle;
import com.chaekcheck.library.SearchTarget;

/** One library page a batch may request, in plan order. */
public record Candidate(SearchTarget target, int page) implements LookupTask {

	@Override
	public String hostKey() {
		return HostThrottle.key(URI.create(target.baseUrl()));
	}

}
