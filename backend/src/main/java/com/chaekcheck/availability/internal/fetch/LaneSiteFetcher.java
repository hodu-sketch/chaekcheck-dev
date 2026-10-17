package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.SSLContext;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.EgressLane;

/**
 * Direct mode with egress lanes (webshare cycle (in cycle 20), handoff 1-15 #2): one HTTP client per lane.
 * <ul>
 * <li>The direct lane keeps the JDK client ({@link DirectSiteFetcher}, {@link HttpClient.Builder#NO_PROXY} so no
 * system proxy setting can take it elsewhere) plus the Apache client for underscore host names
 * ({@link UnderscoreHostSiteFetcher}), routed by {@link HostRoutingSiteFetcher}.</li>
 * <li>A proxy lane uses Apache HttpClient 5 for <em>every</em> host ({@link UnderscoreHostSiteFetcher#forProxyLane}):
 * all requests go through the lane's proxy, https sites as a CONNECT tunnel. Webshare answers an unauthenticated
 * CONNECT with 407 and no {@code Proxy-Authenticate} challenge, which the JDK client waits for and never gets
 * (connect timeout), so proxy credentials (one pair for every proxy lane; empty = the vendor authorizes the
 * server's IP) are sent preemptively on the first request. Credentials or not, a proxy lane is one client and one
 * behaviour, so the local check with credentials exercises the client production uses without them.</li>
 * </ul>
 * TLS, timeouts, the body limit, gzip, the User-Agent and the {@code upstream … lane= vendor= … bytes=} log line are
 * the same on every lane.
 */
public final class LaneSiteFetcher implements SiteFetcher, AutoCloseable {

	private final Map<String, SiteFetcher> byLane = new LinkedHashMap<>();

	/**
	 * @param credentials {@code {username, password}} for every proxy lane, or null
	 */
	public LaneSiteFetcher(List<EgressLane> lanes, SSLContext sslContext, Duration connectTimeout,
			Duration readTimeout, Duration totalTimeout, long maxBodyBytes, String userAgent, String[] credentials,
			Clock clock) {
		for (EgressLane lane : lanes) {
			if (lane.isDirect()) {
				HttpClient jdk = HttpClient.newBuilder()
					.sslContext(sslContext)
					.connectTimeout(connectTimeout)
					.followRedirects(HttpClient.Redirect.NEVER)
					.proxy(HttpClient.Builder.NO_PROXY)
					.build();
				DirectSiteFetcher direct = new DirectSiteFetcher(jdk, readTimeout, totalTimeout, maxBodyBytes, userAgent,
						clock);
				UnderscoreHostSiteFetcher underscore = UnderscoreHostSiteFetcher.create(sslContext, connectTimeout,
						readTimeout, totalTimeout, maxBodyBytes, userAgent, clock);
				byLane.put(lane.id(), new HostRoutingSiteFetcher(direct, underscore));
			}
			else {
				byLane.put(lane.id(), UnderscoreHostSiteFetcher.forProxyLane(sslContext, connectTimeout, readTimeout,
						totalTimeout, maxBodyBytes, userAgent, clock, lane.proxy(), credentials));
			}
		}
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline, EgressLane lane) {
		SiteFetcher fetcher = byLane.get(lane.id());
		if (fetcher == null) {
			throw UpstreamException.error("no HTTP client for egress lane " + lane.id());
		}
		return fetcher.fetch(uri, deadline, lane);
	}

	@Override
	public void close() throws Exception {
		for (SiteFetcher fetcher : byLane.values()) {
			if (fetcher instanceof AutoCloseable closeable) {
				closeable.close();
			}
		}
	}

}
