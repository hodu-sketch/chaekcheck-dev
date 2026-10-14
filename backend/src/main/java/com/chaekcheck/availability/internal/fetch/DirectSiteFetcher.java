package com.chaekcheck.availability.internal.fetch;

import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Calls the library site from this server (cycle 01 {@code UpstreamClient}). Every request carries the configured
 * User-Agent and is logged with host, path, start and end time, status and User-Agent. Redirects are not followed.
 * Limits (application.yml {@code chaekcheck.availability.*}): the client's connect timeout, the read timeout (until
 * the response headers), a total time limit for the whole request including the body (TIMEOUT), and a maximum body
 * size: a bigger body stops being read and the request fails as ERROR (upstream_error).
 */
public class DirectSiteFetcher implements SiteFetcher {

	static final String VIA = "direct";

	private static final Logger log = LoggerFactory.getLogger(DirectSiteFetcher.class);

	private final HttpClient httpClient;

	private final Duration readTimeout;

	private final Duration totalTimeout;

	private final long maxBodyBytes;

	private final String userAgent;

	private final Clock clock;

	/**
	 * @param httpClient carries the connect timeout and TLS settings; must not follow redirects
	 * @param readTimeout until the response headers arrive
	 * @param totalTimeout the whole request, body included
	 * @param maxBodyBytes largest body read into memory
	 */
	public DirectSiteFetcher(HttpClient httpClient, Duration readTimeout, Duration totalTimeout, long maxBodyBytes,
			String userAgent, Clock clock) {
		this.httpClient = httpClient;
		this.readTimeout = readTimeout;
		this.totalTimeout = totalTimeout;
		this.maxBodyBytes = maxBodyBytes;
		this.userAgent = userAgent;
		this.clock = clock;
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline) {
		Instant start = clock.instant();
		String status = "-";
		CompletableFuture<HttpResponse<byte[]>> call = null;
		try {
			HttpRequest request;
			try {
				request = HttpRequest.newBuilder(uri)
					.timeout(readTimeout)
					.header(HttpHeaders.USER_AGENT, userAgent)
					.GET()
					.build();
			}
			catch (IllegalArgumentException ex) {
				// java.net.http refuses an address it cannot read the host of ("unsupported URI"); only this
				// library's row fails (cycle 18). Underscore hosts do not get here (UnderscoreHostSiteFetcher).
				status = "bad-uri";
				throw new UpstreamException(UpstreamException.Kind.ERROR, "cannot request " + uri + ": " + ex, ex);
			}
			call = httpClient.sendAsync(request, BodyLimit.handler(maxBodyBytes));
			HttpResponse<byte[]> res = call.get(totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
			status = Integer.toString(res.statusCode());
			return new SiteResponse(res.statusCode(), res.headers().firstValue(HttpHeaders.CONTENT_TYPE).orElse(null),
					res.headers().firstValue(HttpHeaders.LOCATION).orElse(null), res.body(), VIA);
		}
		catch (TimeoutException ex) {
			call.cancel(true);
			status = "total-timeout";
			throw UpstreamException.timeout("request to " + SiteHostNames.hostOrNull(uri) + " passed the total time limit of "
					+ totalTimeout.toMillis() + "ms");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (has(cause, BodyLimit.TooLargeException.class)) {
				status = "body-too-large";
				throw new UpstreamException(UpstreamException.Kind.ERROR,
						"response from " + SiteHostNames.hostOrNull(uri) + " is over " + maxBodyBytes + " bytes", cause);
			}
			boolean timeout = isTimeout(cause);
			status = timeout ? "timeout" : "io-error";
			throw new UpstreamException(timeout ? UpstreamException.Kind.TIMEOUT : UpstreamException.Kind.ERROR,
					"request to " + SiteHostNames.hostOrNull(uri) + " failed: " + cause, cause);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			call.cancel(true);
			status = "interrupted";
			throw new UpstreamException(UpstreamException.Kind.ERROR, "request to " + SiteHostNames.hostOrNull(uri) + " interrupted",
					ex);
		}
		finally {
			log.info("upstream via={} host={} path={} start={} end={} status={} userAgent=\"{}\"", VIA,
					HostThrottle.hostPort(uri), uri.getRawPath(), start, clock.instant(), status, userAgent);
		}
	}

	static boolean isTimeout(Throwable ex) {
		return has(ex, HttpTimeoutException.class) || has(ex, SocketTimeoutException.class);
	}

	private static boolean has(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

}
