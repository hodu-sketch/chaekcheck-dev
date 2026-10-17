package com.chaekcheck.availability.internal.fetch;

import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.ProxySelector;
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
 * Direct mode with egress lanes (cycle 22, handoff 1-15 #2): one HTTP client pair per lane — the JDK client
 * ({@link DirectSiteFetcher}) and the Apache client for underscore host names ({@link UnderscoreHostSiteFetcher}),
 * routed by {@link HostRoutingSiteFetcher}. A proxy lane's clients send everything through the lane's proxy
 * ({@link ProxySelector#of}; https sites go through a CONNECT tunnel); the direct lane's clients use
 * {@link HttpClient.Builder#NO_PROXY}, so no system proxy setting can take them elsewhere. TLS, timeouts, the body
 * limit, gzip and the User-Agent are the same on every lane.
 * <p>
 * Proxy credentials (one pair for every proxy lane; empty = the vendor authorizes the server's IP): the JDK client
 * answers the proxy's 407 through an {@link Authenticator} (proxy requests only), the Apache client through its
 * credentials provider. Both clients tunnel https with Basic only when
 * {@code jdk.http.auth.tunneling.disabledSchemes} / the Apache default allow it — {@code backend/build.gradle}
 * clears the JDK property for {@code bootRun} and {@code test} (local use); production uses IP authorization and
 * needs none of this.
 */
public final class LaneSiteFetcher implements SiteFetcher, AutoCloseable {

	private final Map<String, HostRoutingSiteFetcher> byLane = new LinkedHashMap<>();

	/**
	 * @param credentials {@code {username, password}} for every proxy lane, or null
	 */
	public LaneSiteFetcher(List<EgressLane> lanes, SSLContext sslContext, Duration connectTimeout,
			Duration readTimeout, Duration totalTimeout, long maxBodyBytes, String userAgent, String[] credentials,
			Clock clock) {
		for (EgressLane lane : lanes) {
			HttpClient.Builder builder = HttpClient.newBuilder()
				.sslContext(sslContext)
				.connectTimeout(connectTimeout)
				.followRedirects(HttpClient.Redirect.NEVER)
				.proxy(lane.isDirect() ? HttpClient.Builder.NO_PROXY : ProxySelector.of(lane.proxy()));
			String[] laneCredentials = lane.isDirect() ? null : credentials;
			if (laneCredentials != null) {
				builder.authenticator(new ProxyAuthenticator(laneCredentials[0], laneCredentials[1]));
			}
			DirectSiteFetcher direct = new DirectSiteFetcher(builder.build(), readTimeout, totalTimeout, maxBodyBytes,
					userAgent, clock);
			UnderscoreHostSiteFetcher underscore = UnderscoreHostSiteFetcher.create(sslContext, connectTimeout,
					readTimeout, totalTimeout, maxBodyBytes, userAgent, clock, lane.proxy(), laneCredentials);
			byLane.put(lane.id(), new HostRoutingSiteFetcher(direct, underscore));
		}
	}

	@Override
	public SiteResponse fetch(URI uri, Instant deadline, EgressLane lane) {
		HostRoutingSiteFetcher fetcher = byLane.get(lane.id());
		if (fetcher == null) {
			throw UpstreamException.error("no HTTP client for egress lane " + lane.id());
		}
		return fetcher.fetch(uri, deadline, lane);
	}

	@Override
	public void close() throws Exception {
		for (HostRoutingSiteFetcher fetcher : byLane.values()) {
			fetcher.close();
		}
	}

	/** Answers only the proxy's challenge, never a site's. */
	private static final class ProxyAuthenticator extends Authenticator {

		private final String username;

		private final char[] password;

		ProxyAuthenticator(String username, String password) {
			this.username = username;
			this.password = password.toCharArray();
		}

		@Override
		protected PasswordAuthentication getPasswordAuthentication() {
			return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(username, password) : null;
		}

	}

}
