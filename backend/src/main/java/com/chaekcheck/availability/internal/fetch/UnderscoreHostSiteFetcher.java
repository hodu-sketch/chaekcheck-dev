package com.chaekcheck.availability.internal.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.ContextBuilder;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.auth.UsernamePasswordCredentials;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.TunnelRefusedException;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.protocol.HttpClientContext;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.EgressLane;

/**
 * Calls a library site whose host name has an underscore (cycle 18, ADR-0111; today only 2624
 * {@code hs_partners.yes24library.com}). {@link java.net.http} refuses such addresses before sending
 * ({@code HttpRequestBuilderImpl.checkURI}, JDK-8180809), so these requests go through Apache HttpClient 5, which
 * reads the host from the raw authority. Everything else stays on {@link DirectSiteFetcher}
 * ({@link HostRoutingSiteFetcher} picks the path).
 * <p>
 * Same rules as the direct path (the four conditions of the cycle 18 handoff, C3):
 * <ul>
 * <li>the same connect timeout, read timeout (until the response headers), total time limit (TIMEOUT) and maximum
 * body size (a bigger body is ERROR), and the same TLS context {@link LibrarySiteTls#sslContext()};</li>
 * <li>the certificate's host name is still checked, by HttpClient's own verifier (wildcards like
 * {@code *.yes24library.com} match). The JDK's built-in check ({@code HostnameVerificationPolicy.BUILTIN}) cannot
 * be used: it turns the name into an {@code SNIHostName}, which refuses underscores;</li>
 * <li>no SNI: the JDK leaves the server name out of the handshake when it is not a valid {@code SNIHostName};</li>
 * <li>no redirects, no retries, no cookies, the client's own content compression off (cycle 20 asks for gzip and
 * inflates it in {@link ContentEncoding}, under the same limit as the direct path), the configured User-Agent, and
 * one log line like the direct path's.</li>
 * </ul>
 * A failure (certificate, timeout, size) fails only that library's row, as on the direct path.
 * <p>
 * Webshare cycle (in cycle 20): the same client is also the <em>whole</em> client of a proxy lane
 * ({@link #forProxyLane}), every host, not only underscore names. Webshare's static residential proxies answer an
 * unauthenticated https {@code CONNECT} with {@code 407} and <em>no</em> {@code Proxy-Authenticate} challenge, and
 * {@link java.net.http.HttpClient} only authenticates after a challenge (and cannot add a header to its own
 * {@code CONNECT}), so with credentials the tunnel never opened (connect timeout after ~5s). Here the Basic
 * {@code Proxy-Authorization} goes out <em>preemptively</em> on the first request, {@code CONNECT} and plain http
 * alike ({@link ContextBuilder#preemptiveBasicAuth}); without credentials (production: the vendor authorizes the
 * server's IP) nothing is sent. A {@code CONNECT} the proxy refuses (407 without credentials, wrong credentials) is
 * returned as a response with that status, as a plain http request through the proxy already is, so
 * {@code BlockSignal} counts both the same way. The log line is the direct path's, {@code bytes=} included
 * (compressed bytes received).
 */
public class UnderscoreHostSiteFetcher implements SiteFetcher, AutoCloseable {

	static final String VIA = "direct-underscore";

	/** Connections one client keeps open through its route; a lane talks to many sites at once. */
	private static final int MAX_CONNECTIONS = 200;

	private static final int MAX_CONNECTIONS_PER_ROUTE = 8;

	/** A pooled connection idle this long is checked before reuse (no retries on this path, ADR-0111). */
	private static final TimeValue VALIDATE_AFTER_INACTIVITY = TimeValue.ofSeconds(1);

	private static final TimeValue CONNECTION_TIME_TO_LIVE = TimeValue.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(UnderscoreHostSiteFetcher.class);

	private static final int READ_CHUNK = 16 * 1024;

	private final CloseableHttpClient httpClient;

	private final Duration totalTimeout;

	private final long maxBodyBytes;

	private final String userAgent;

	private final Clock clock;

	/** The {@code via} of the log line, or null to log the lane's id (whole-lane client of a proxy lane). */
	private final String via;

	/** The lane's proxy, null for the direct lane; credentials null for IP authorization. */
	private final HttpHost proxyHost;

	private final UsernamePasswordCredentials proxyCredentials;

	UnderscoreHostSiteFetcher(CloseableHttpClient httpClient, Duration totalTimeout, long maxBodyBytes,
			String userAgent, Clock clock) {
		this(httpClient, totalTimeout, maxBodyBytes, userAgent, clock, VIA, null, null);
	}

	private UnderscoreHostSiteFetcher(CloseableHttpClient httpClient, Duration totalTimeout, long maxBodyBytes,
			String userAgent, Clock clock, String via, HttpHost proxyHost, UsernamePasswordCredentials proxyCredentials) {
		this.httpClient = httpClient;
		this.totalTimeout = totalTimeout;
		this.maxBodyBytes = maxBodyBytes;
		this.userAgent = userAgent;
		this.clock = clock;
		this.via = via;
		this.proxyHost = proxyHost;
		this.proxyCredentials = proxyCredentials;
	}

	/**
	 * @param connectTimeout as the direct path's client
	 * @param readTimeout until the response headers (also the longest pause while reading the body)
	 * @param totalTimeout the whole request, body included
	 * @param maxBodyBytes largest body read into memory
	 */
	public static UnderscoreHostSiteFetcher create(SSLContext sslContext, Duration connectTimeout,
			Duration readTimeout, Duration totalTimeout, long maxBodyBytes, String userAgent, Clock clock) {
		return create(sslContext, connectTimeout, readTimeout, totalTimeout, maxBodyBytes, userAgent, clock,
				SystemDefaultDnsResolver.INSTANCE, null, null, VIA);
	}

	/**
	 * The whole client of one proxy lane (webshare cycle (in cycle 20)): every host of that lane, underscore names
	 * included, through {@code proxy} (https sites as a CONNECT tunnel). With {@code credentials}, Basic
	 * {@code Proxy-Authorization} is sent preemptively on every request to the proxy, the first CONNECT included;
	 * the log line says {@code via=<lane id>} like the direct path's.
	 * @param proxy the lane's proxy
	 * @param credentials {@code {username, password}}, or null for IP authorization
	 */
	public static UnderscoreHostSiteFetcher forProxyLane(SSLContext sslContext, Duration connectTimeout,
			Duration readTimeout, Duration totalTimeout, long maxBodyBytes, String userAgent, Clock clock,
			InetSocketAddress proxy, String[] credentials) {
		return create(sslContext, connectTimeout, readTimeout, totalTimeout, maxBodyBytes, userAgent, clock,
				SystemDefaultDnsResolver.INSTANCE, proxy, credentials, null);
	}

	/** With a DNS resolver of its own, for tests that point an underscore name at a local server. */
	static UnderscoreHostSiteFetcher create(SSLContext sslContext, Duration connectTimeout, Duration readTimeout,
			Duration totalTimeout, long maxBodyBytes, String userAgent, Clock clock, DnsResolver dnsResolver) {
		return create(sslContext, connectTimeout, readTimeout, totalTimeout, maxBodyBytes, userAgent, clock,
				dnsResolver, null, null, VIA);
	}

	/**
	 * @param proxy the lane's proxy, or null for the direct lane
	 * @param credentials {@code {username, password}} sent preemptively to {@code proxy}, or null
	 * @param via the log line's {@code via}, or null for the lane's id
	 */
	static UnderscoreHostSiteFetcher create(SSLContext sslContext, Duration connectTimeout, Duration readTimeout,
			Duration totalTimeout, long maxBodyBytes, String userAgent, Clock clock, DnsResolver dnsResolver,
			InetSocketAddress proxy, String[] credentials, String via) {
		ClientTlsStrategyBuilder tls = ClientTlsStrategyBuilder.create()
			.setSslContext(sslContext)
			.setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
			.setHostnameVerifier(new DefaultHostnameVerifier());
		HttpHost proxyHost = proxy == null ? null : new HttpHost("http", proxy.getHostString(), proxy.getPort());
		UsernamePasswordCredentials proxyCredentials = proxyHost == null || credentials == null ? null
				: new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray());
		CloseableHttpClient client = HttpClients.custom()
			.setProxy(proxyHost)
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setTlsSocketStrategy(tls.buildClassic())
				.setDnsResolver(dnsResolver)
				.setMaxConnTotal(MAX_CONNECTIONS)
				.setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.of(connectTimeout))
					.setSocketTimeout(Timeout.of(readTimeout))
					.setValidateAfterInactivity(VALIDATE_AFTER_INACTIVITY)
					.setTimeToLive(CONNECTION_TIME_TO_LIVE)
					.build())
				.build())
			.setDefaultRequestConfig(RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.of(connectTimeout))
				.setResponseTimeout(Timeout.of(readTimeout))
				.build())
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.disableContentCompression()
			// no auth cache across requests: each request's context carries the proxy credentials itself
			.disableAuthCaching()
			.setUserAgent(userAgent)
			.build();
		return new UnderscoreHostSiteFetcher(client, totalTimeout, maxBodyBytes, userAgent, clock, via, proxyHost,
				proxyCredentials);
	}

	/**
	 * The context of one request: with proxy credentials, a Basic scheme already holding them for the proxy host,
	 * so the first request (CONNECT or plain http) carries {@code Proxy-Authorization} and no 407 round trip is
	 * needed — Webshare sends no challenge to a CONNECT.
	 */
	private HttpClientContext context() {
		return proxyCredentials == null ? HttpClientContext.create()
				: ContextBuilder.create().preemptiveBasicAuth(proxyHost, proxyCredentials).build();
	}

	/** {@code lane} names the lane this client was built for (webshare cycle (in cycle 20)), for {@code via} and the log line. */
	@Override
	public SiteResponse fetch(URI uri, Instant deadline, EgressLane lane) {
		Instant start = clock.instant();
		String host = SiteHostNames.hostOrNull(uri);
		String status = "-";
		// compressed bytes received (cycle 17 counts vendor data from this field); -1 until a body arrived
		long[] bytes = { -1 };
		HttpGet get = new HttpGet(uri);
		get.setHeader(HttpHeaders.USER_AGENT, userAgent);
		get.setHeader(HttpHeaders.ACCEPT_ENCODING, ContentEncoding.GZIP);
		FutureTask<SiteResponse> call = new FutureTask<>(
				() -> httpClient.execute(get, context(), response -> read(response, lane, bytes)));
		try {
			Thread.ofVirtual().name("underscore-host-fetch").start(call);
			SiteResponse response = call.get(totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
			status = Integer.toString(response.status());
			return response;
		}
		catch (TimeoutException ex) {
			get.cancel();
			call.cancel(true);
			status = "total-timeout";
			throw UpstreamException.timeout("request to " + host + " passed the total time limit of "
					+ totalTimeout.toMillis() + "ms");
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			BodyLimit.TooLargeException tooLarge = DirectSiteFetcher.find(cause, BodyLimit.TooLargeException.class);
			if (tooLarge != null) {
				status = "body-too-large";
				throw UpstreamException.bodyTooLarge(uri, maxBodyBytes, tooLarge.received(), cause);
			}
			TunnelRefusedException refused = DirectSiteFetcher.find(cause, TunnelRefusedException.class);
			if (refused != null) {
				// the proxy refused the CONNECT (407: not authorized, or any other code): the answer is the proxy's
				// status, as it already is for a plain http request through the proxy, so BlockSignal sees one 407
				int code = refused.getResponse().getCode();
				status = Integer.toString(code);
				return new SiteResponse(code, null, null, new byte[0], lane.id());
			}
			boolean timeout = DirectSiteFetcher.isTimeout(cause);
			status = timeout ? "timeout" : "io-error";
			throw new UpstreamException(timeout ? UpstreamException.Kind.TIMEOUT : UpstreamException.Kind.ERROR,
					"request to " + host + " failed: " + cause, cause);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			get.cancel();
			call.cancel(true);
			status = "interrupted";
			throw new UpstreamException(UpstreamException.Kind.ERROR, "request to " + host + " interrupted", ex);
		}
		finally {
			log.info("upstream via={} lane={} vendor={} host={} path={} start={} end={} status={} bytes={} userAgent=\"{}\"",
					via == null ? lane.id() : via, lane.id(), lane.vendor(), HostThrottle.hostPort(uri), uri.getRawPath(),
					start, clock.instant(), status, bytes[0], userAgent);
		}
	}

	private SiteResponse read(ClassicHttpResponse response, EgressLane lane, long[] bytes) throws IOException {
		byte[] body = body(response.getEntity());
		bytes[0] = body.length;
		// the same limit again on the inflated bytes (cycle 20); another encoding fails this library alone
		byte[] decoded = ContentEncoding.decoded(value(response.getFirstHeader(HttpHeaders.CONTENT_ENCODING)), body,
				maxBodyBytes);
		return new SiteResponse(response.getCode(), value(response.getFirstHeader(HttpHeaders.CONTENT_TYPE)),
				value(response.getFirstHeader(HttpHeaders.LOCATION)), decoded, lane.id());
	}

	/** The body, at most {@code maxBodyBytes}; more (declared or read) fails like {@link BodyLimit}. */
	private byte[] body(HttpEntity entity) throws IOException {
		if (entity == null) {
			return new byte[0];
		}
		long declared = entity.getContentLength();
		if (declared > maxBodyBytes) {
			throw new BodyLimit.TooLargeException(maxBodyBytes, declared);
		}
		byte[] body = new byte[(int) (declared >= 0 ? declared : Math.min(READ_CHUNK, maxBodyBytes))];
		int received = 0;
		try (InputStream in = entity.getContent()) {
			while (true) {
				if (received == body.length) {
					int next = in.read();
					if (next == -1) {
						return body;
					}
					// no or a wrong Content-Length: grow by doubling, never past the limit
					if (received + 1L > maxBodyBytes) {
						throw new BodyLimit.TooLargeException(maxBodyBytes, received + 1L);
					}
					body = Arrays.copyOf(body, (int) Math.min(maxBodyBytes, Math.max(received + 1L, 2L * body.length)));
					body[received++] = (byte) next;
					continue;
				}
				int n = in.read(body, received, body.length - received);
				if (n == -1) {
					return received == body.length ? body : Arrays.copyOf(body, received);
				}
				received += n;
			}
		}
	}

	private static String value(Header header) {
		return header == null ? null : header.getValue();
	}

	private static boolean has(Throwable ex, Class<? extends Throwable> type) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() throws IOException {
		httpClient.close();
	}

}
