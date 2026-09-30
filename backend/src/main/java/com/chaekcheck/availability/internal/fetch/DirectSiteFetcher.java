package com.chaekcheck.availability.internal.fetch;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Calls the library site from this server (cycle 01 {@code UpstreamClient}). Every request carries the configured
 * User-Agent and is logged with host, path, start and end time, status and User-Agent. Timeouts come from the
 * request factory (connect 3s, read 5s); redirects are not followed.
 */
public class DirectSiteFetcher implements SiteFetcher {

	static final String VIA = "direct";

	private static final Logger log = LoggerFactory.getLogger(DirectSiteFetcher.class);

	private final RestClient restClient;

	private final String userAgent;

	private final Clock clock;

	public DirectSiteFetcher(RestClient restClient, String userAgent, Clock clock) {
		this.restClient = restClient;
		this.userAgent = userAgent;
		this.clock = clock;
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline) {
		Instant start = clock.instant();
		String status = "-";
		try {
			SiteResponse response = restClient.get()
				.uri(uri)
				.header(HttpHeaders.USER_AGENT, userAgent)
				.exchange((request, res) -> new SiteResponse(res.getStatusCode().value(),
						res.getHeaders().getFirst(HttpHeaders.CONTENT_TYPE),
						res.getHeaders().getFirst(HttpHeaders.LOCATION), res.getBody().readAllBytes(), VIA));
			status = Integer.toString(response.status());
			return response;
		}
		catch (ResourceAccessException ex) {
			boolean timeout = isTimeout(ex);
			status = timeout ? "timeout" : "io-error";
			throw new UpstreamException(timeout ? UpstreamException.Kind.TIMEOUT : UpstreamException.Kind.ERROR,
					"request to " + uri.getHost() + " failed: " + ex.getMessage(), ex);
		}
		finally {
			log.info("upstream via={} host={} path={} start={} end={} status={} userAgent=\"{}\"", VIA,
					HostThrottle.hostPort(uri), uri.getRawPath(), start, clock.instant(), status, userAgent);
		}
	}

	static boolean isTimeout(Throwable ex) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (t instanceof HttpTimeoutException || t instanceof SocketTimeoutException) {
				return true;
			}
		}
		return false;
	}

}
