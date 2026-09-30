package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Instant;

/**
 * GETs one library page (ADR-0015). Two implementations: {@link DirectSiteFetcher} calls the site from this server,
 * {@link FetchServiceSiteFetcher} goes through a fetch server. Chosen by
 * {@code chaekcheck.availability.fetch.mode}; there is no automatic switch between them.
 * <p>
 * Cache and host throttle sit in front of this interface ({@link LibraryPageFetcher}), so both implementations
 * follow the same rules. Redirects are never followed.
 */
public interface SiteFetcher {

	/**
	 * @param deadline the latest time an answer is useful; the fetch-service implementation spends at most this
	 * long trying servers
	 * @return the site's answer, whatever its status
	 * @throws com.chaekcheck.availability.internal.UpstreamException TIMEOUT when the site (or the fetch server)
	 * did not answer in time, ERROR when there was no answer for another reason
	 */
	SiteResponse fetch(URI uri, Instant deadline);

}
