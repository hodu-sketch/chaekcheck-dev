package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Instant;

/**
 * Picks the request path for one address (cycle 18, ADR-0111): an address whose host has an underscore
 * ({@link SiteHostNames#needsUnderscorePath}) goes to {@link UnderscoreHostSiteFetcher}, every other address to the
 * direct path as before. Only used in direct mode; in fetch-service mode the fetch server makes the same choice.
 */
public class HostRoutingSiteFetcher implements SiteFetcher, AutoCloseable {

	private final SiteFetcher direct;

	private final SiteFetcher underscoreHost;

	public HostRoutingSiteFetcher(SiteFetcher direct, SiteFetcher underscoreHost) {
		this.direct = direct;
		this.underscoreHost = underscoreHost;
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline) {
		return SiteHostNames.needsUnderscorePath(uri) ? underscoreHost.fetch(uri, deadline)
				: direct.fetch(uri, deadline);
	}

	/** Closes the underscore path's HTTP client when the application stops (the JDK client needs no closing). */
	@Override
	public void close() throws Exception {
		if (underscoreHost instanceof AutoCloseable closeable) {
			closeable.close();
		}
	}

}
