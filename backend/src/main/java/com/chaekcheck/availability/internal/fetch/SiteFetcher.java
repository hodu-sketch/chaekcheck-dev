package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Instant;

import com.chaekcheck.availability.internal.egress.EgressLane;

/**
 * GETs one library page (ADR-0015) through one egress lane (webshare cycle (in cycle 20), ADR-0130). Two implementations:
 * {@link LaneSiteFetcher} calls the site from this server with the lane's own HTTP client (direct, or through the
 * lane's proxy), {@link FetchServiceSiteFetcher} goes through a fetch server and names the lane in the request.
 * Chosen by {@code chaekcheck.availability.fetch.mode}; there is no automatic switch between them.
 * <p>
 * Cache, lane choice and the per-lane host throttle sit in front of this interface
 * ({@code LibraryPageFetcher} → {@code EgressLanes}), so both implementations follow the same rules. Redirects are
 * never followed.
 */
public interface SiteFetcher {

	/**
	 * @param deadline the latest time an answer is useful; the fetch-service implementation spends at most this
	 * long trying servers
	 * @param lane the lane to send through, chosen by the caller
	 * @return the site's answer, whatever its status; {@code via} is the lane id
	 * @throws com.chaekcheck.availability.internal.UpstreamException TIMEOUT when the site (or the fetch server)
	 * did not answer in time, ERROR when there was no answer for another reason
	 */
	SiteResponse fetch(URI uri, Instant deadline, EgressLane lane);

}
