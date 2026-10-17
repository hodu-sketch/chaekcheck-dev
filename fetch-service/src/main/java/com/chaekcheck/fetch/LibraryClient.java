package com.chaekcheck.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.ConnectTimeoutException;
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
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * GETs a library page: GET only, redirects not followed, fixed User-Agent. Limits (application.yml {@code fetch.*}):
 * connect timeout, read timeout (until the response headers), a total time limit for the whole request including
 * the body, and a maximum body size; a bigger body stops being read and the call fails as BODY_TOO_LARGE (cycle 20;
 * ERROR before). The request asks for gzip and the answer is inflated here under the same limit
 * ({@link ContentEncoding}). No cache and no host throttling (those live in the main service, ADR-0011). Every call
 * is logged with host, path, start, end, status and User-Agent.
 * <p>
 * Cycle 18 (ADR-0111): a host name with an underscore goes through Apache HttpClient 5 instead (java.net.http refuses
 * it), with the same limits, outcomes and log line ({@link #getUnderscoreHost}).
 * <p>
 * Webshare cycle (in cycle 20) (ADR-0130, handoff 1-15 #5): one client per egress lane the mode allows. The direct
 * lane keeps the JDK client ({@link HttpClient.Builder#NO_PROXY}) plus the Apache client for underscore names. A
 * proxy lane is one Apache client for every host, with the proxy as its route (https as a CONNECT tunnel) and the
 * optional proxy credentials (one pair) sent <em>preemptively</em> on the first request: Webshare answers an
 * unauthenticated CONNECT with 407 and no {@code Proxy-Authenticate} challenge, which the JDK client waits for
 * (connect timeout). A CONNECT the proxy refuses is answered as RESPONDED with the proxy's status, as a plain http
 * request through the proxy already is. The main service picks the lane; here only {@link #lane(String)} maps
 * the id to its client.
 */
@Component
class LibraryClient implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LibraryClient.class);

	private final Map<String, LaneClients> lanes = new LinkedHashMap<>();

	private final FetchProperties properties;

	private final Clock clock;

	@Autowired
	LibraryClient(FetchProperties properties) {
		this(properties, SystemDefaultDnsResolver.INSTANCE);
	}

	/** With a DNS resolver of its own for the Apache path, for tests that point an underscore name at a local server. */
	LibraryClient(FetchProperties properties, DnsResolver apacheDns) {
		this(properties, apacheDns, LibrarySiteTls.sslContext());
	}

	/** With a TLS context of its own too, for tests with a local https server. */
	LibraryClient(FetchProperties properties, DnsResolver apacheDns, SSLContext sslContext) {
		this.properties = properties;
		this.clock = Clock.systemUTC();
		List<EgressLane> allowed = properties.egress().allowedLanes();
		if (allowed.isEmpty()) {
			throw new IllegalStateException("fetch.egress.mode " + properties.egress().mode() + " leaves no lane");
		}
		String[] credentials = properties.egress().credentials();
		for (EgressLane lane : allowed) {
			if (lane.isDirect()) {
				HttpClient jdk = HttpClient.newBuilder()
					.sslContext(sslContext)
					.connectTimeout(properties.connectTimeout())
					.followRedirects(HttpClient.Redirect.NEVER)
					.proxy(HttpClient.Builder.NO_PROXY)
					.build();
				lanes.put(lane.id(), new LaneClients(lane, jdk,
						apacheClient(properties, sslContext, apacheDns, null), null, null));
			}
			else {
				HttpHost proxyHost = new HttpHost("http", lane.proxy().getHostString(), lane.proxy().getPort());
				lanes.put(lane.id(), new LaneClients(lane, null, apacheClient(properties, sslContext, apacheDns, proxyHost),
						proxyHost, credentials == null ? null
								: new UsernamePasswordCredentials(credentials[0], credentials[1].toCharArray())));
			}
		}
		long proxies = allowed.stream().filter(lane -> !lane.isDirect()).count();
		// no addresses in this line
		log.info("egress lanes mode={} lanes={} direct={} proxies={} vendor={}", properties.egress().mode(),
				allowed.size(), allowed.size() - proxies, proxies,
				allowed.stream().filter(lane -> !lane.isDirect()).map(EgressLane::vendor).findFirst().orElse("-"));
	}

	/** The lane with this id among those the mode allows, or null. */
	EgressLane lane(String id) {
		LaneClients clients = lanes.get(id);
		return clients == null ? null : clients.lane();
	}

	/**
	 * The clients of one lane: the direct lane has the JDK client and the Apache client for underscore names; a proxy
	 * lane has only the Apache client ({@code httpClient} null), with its proxy and, when configured, credentials.
	 */
	private record LaneClients(EgressLane lane, HttpClient httpClient, CloseableHttpClient apacheClient,
			HttpHost proxyHost, UsernamePasswordCredentials proxyCredentials) {

		/**
		 * The context of one request: with credentials, a Basic scheme already holding them for the proxy, so the
		 * first request (CONNECT or plain http) carries {@code Proxy-Authorization} without a 407 round trip.
		 */
		HttpClientContext context() {
			return proxyCredentials == null ? HttpClientContext.create()
					: ContextBuilder.create().preemptiveBasicAuth(proxyHost, proxyCredentials).build();
		}

	}

	/** Connections one client keeps open through its route; a proxy lane talks to many sites at once. */
	private static final int MAX_CONNECTIONS = 200;

	private static final int MAX_CONNECTIONS_PER_ROUTE = 8;

	/**
	 * Apache HttpClient 5: for host names with an underscore on the direct lane (cycle 18, ADR-0111) and for every
	 * host of a proxy lane (webshare cycle), with the same limits and TLS as the JDK client. The certificate's host
	 * name is checked by HttpClient's own verifier (the JDK's built-in check refuses underscore names); no SNI goes
	 * out for a name that is not a valid SNIHostName. No redirects, retries, cookies, content compression or auth
	 * cache across requests (each request's context carries the proxy credentials itself); a pooled connection idle
	 * for a second is checked before reuse.
	 * @param proxyHost the lane's proxy, or null for the direct lane
	 */
	private static CloseableHttpClient apacheClient(FetchProperties properties, SSLContext sslContext,
			DnsResolver dns, HttpHost proxyHost) {
		ClientTlsStrategyBuilder tls = ClientTlsStrategyBuilder.create()
			.setSslContext(sslContext)
			.setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
			.setHostnameVerifier(new DefaultHostnameVerifier());
		return HttpClients.custom()
			.setProxy(proxyHost)
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setTlsSocketStrategy(tls.buildClassic())
				.setDnsResolver(dns)
				.setMaxConnTotal(MAX_CONNECTIONS)
				.setMaxConnPerRoute(MAX_CONNECTIONS_PER_ROUTE)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.of(properties.connectTimeout()))
					.setSocketTimeout(Timeout.of(properties.readTimeout()))
					.setValidateAfterInactivity(TimeValue.ofSeconds(1))
					.setTimeToLive(TimeValue.ofMinutes(1))
					.build())
				.build())
			.setDefaultRequestConfig(RequestConfig.custom()
				.setConnectionRequestTimeout(Timeout.of(properties.connectTimeout()))
				.setResponseTimeout(Timeout.of(properties.readTimeout()))
				.build())
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.disableContentCompression()
			.disableAuthCaching()
			.setUserAgent(properties.userAgent())
			.build();
	}

	/** @param lane one of {@link #lane(String)}'s lanes */
	FetchResult get(URI uri, String requestId, EgressLane lane) {
		LaneClients clients = lanes.get(lane.id());
		if (clients.httpClient() == null || UnderscoreHosts.isUnderscoreHost(uri)) {
			return getWithApache(uri, requestId, clients);
		}
		HttpClient httpClient = clients.httpClient();
		Instant start = clock.instant();
		String status = "-";
		CompletableFuture<HttpResponse<byte[]>> call = null;
		try {
			HttpRequest request;
			try {
				request = HttpRequest.newBuilder(uri)
					.timeout(properties.readTimeout())
					.header("User-Agent", properties.userAgent())
					.header("Accept-Encoding", ContentEncoding.GZIP)
					.GET()
					.build();
			}
			catch (IllegalArgumentException ex) {
				// an address java.net.http cannot send ("unsupported URI"); fails alone (cycle 18)
				status = "bad-uri";
				return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
			}
			call = httpClient.sendAsync(request, BodyLimit.handler(properties.maxBodySize().toBytes()));
			HttpResponse<byte[]> response = call.get(properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
			status = Integer.toString(response.statusCode());
			byte[] body;
			try {
				body = ContentEncoding.decoded(response.headers().firstValue("Content-Encoding").orElse(null),
						response.body(), properties.maxBodySize().toBytes());
			}
			catch (BodyLimit.TooLargeException ex) {
				// a compression bomb: the inflated bytes passed the limit the wire bytes kept
				status = "body-too-large";
				return FetchResult.bodyTooLarge(response.body().length, start, clock.instant());
			}
			catch (IOException ex) {
				status = "bad-encoding";
				return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
			}
			return new FetchResult(FetchResult.Outcome.RESPONDED, response.statusCode(),
					response.headers().firstValue("Content-Type").orElse(null),
					response.headers().firstValue("Location").orElse(null),
					Base64.getEncoder().encodeToString(body), start, clock.instant(), null, lane.id());
		}
		catch (TimeoutException ex) {
			// the whole request, body included, passed the total time limit
			call.cancel(true);
			status = "total-timeout";
			return FetchResult.failed(FetchResult.Outcome.TIMEOUT, start, clock.instant());
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			BodyLimit.TooLargeException tooLarge = find(cause, BodyLimit.TooLargeException.class);
			if (tooLarge != null) {
				status = "body-too-large";
				return FetchResult.bodyTooLarge(tooLarge.received(), start, clock.instant());
			}
			if (has(cause, HttpConnectTimeoutException.class) || has(cause, ConnectException.class)) {
				status = "connect-failed";
				return FetchResult.failed(FetchResult.Outcome.CONNECT_FAILED, start, clock.instant());
			}
			if (has(cause, HttpTimeoutException.class)) {
				status = "timeout";
				return FetchResult.failed(FetchResult.Outcome.TIMEOUT, start, clock.instant());
			}
			status = "io-error";
			return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			call.cancel(true);
			status = "interrupted";
			return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
		}
		finally {
			log.info("library host={} path={} requestId={} lane={} vendor={} start={} end={} status={} userAgent=\"{}\"",
					UnderscoreHosts.host(uri), uri.getRawPath(), requestId, lane.id(), lane.vendor(), start,
					clock.instant(), status, properties.userAgent());
		}
	}

	/** Same limits, outcomes and log line as {@link #get}, through the lane's Apache client. */
	private FetchResult getWithApache(URI uri, String requestId, LaneClients clients) {
		EgressLane lane = clients.lane();
		Instant start = clock.instant();
		String status = "-";
		HttpGet get = new HttpGet(uri);
		get.setHeader("User-Agent", properties.userAgent());
		get.setHeader("Accept-Encoding", ContentEncoding.GZIP);
		FutureTask<FetchResult> call = new FutureTask<>(() -> clients.apacheClient().execute(get, clients.context(), response -> {
			byte[] body = ContentEncoding.decoded(value(response.getFirstHeader("Content-Encoding")),
					body(response.getEntity(), properties.maxBodySize().toBytes()), properties.maxBodySize().toBytes());
			return new FetchResult(FetchResult.Outcome.RESPONDED, response.getCode(),
					value(response.getFirstHeader("Content-Type")), value(response.getFirstHeader("Location")),
					Base64.getEncoder().encodeToString(body), start, clock.instant(), null, lane.id());
		}));
		try {
			Thread.ofVirtual().name("underscore-host-fetch").start(call);
			FetchResult result = call.get(properties.totalTimeout().toMillis(), TimeUnit.MILLISECONDS);
			status = Integer.toString(result.status());
			return result;
		}
		catch (TimeoutException ex) {
			get.cancel();
			call.cancel(true);
			status = "total-timeout";
			return FetchResult.failed(FetchResult.Outcome.TIMEOUT, start, clock.instant());
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			BodyLimit.TooLargeException tooLarge = find(cause, BodyLimit.TooLargeException.class);
			if (tooLarge != null) {
				status = "body-too-large";
				return FetchResult.bodyTooLarge(tooLarge.received(), start, clock.instant());
			}
			TunnelRefusedException refused = find(cause, TunnelRefusedException.class);
			if (refused != null) {
				// the proxy refused the CONNECT (407: not authorized, or any other code): answered as the proxy's
				// status, as a plain http request through the proxy is, so the main service counts one 407
				int code = refused.getResponse().getCode();
				status = Integer.toString(code);
				return new FetchResult(FetchResult.Outcome.RESPONDED, code, null, null, "", start, clock.instant(), null,
						lane.id());
			}
			if (has(cause, ConnectTimeoutException.class) || has(cause, ConnectException.class)) {
				status = "connect-failed";
				return FetchResult.failed(FetchResult.Outcome.CONNECT_FAILED, start, clock.instant());
			}
			if (has(cause, SocketTimeoutException.class)) {
				status = "timeout";
				return FetchResult.failed(FetchResult.Outcome.TIMEOUT, start, clock.instant());
			}
			status = "io-error";
			return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			get.cancel();
			call.cancel(true);
			status = "interrupted";
			return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
		}
		finally {
			log.info("library host={} path={} requestId={} lane={} vendor={} start={} end={} status={} userAgent=\"{}\"",
					UnderscoreHosts.host(uri), uri.getRawPath(), requestId, lane.id(), lane.vendor(), start,
					clock.instant(), status, properties.userAgent());
		}
	}

	/** The body, at most {@code maxBytes}; more (declared or read) fails like {@link BodyLimit}. */
	private static byte[] body(HttpEntity entity, long maxBytes) throws IOException {
		if (entity == null) {
			return new byte[0];
		}
		long declared = entity.getContentLength();
		if (declared > maxBytes) {
			throw new BodyLimit.TooLargeException(maxBytes, declared);
		}
		byte[] body = new byte[(int) (declared >= 0 ? declared : Math.min(16 * 1024, maxBytes))];
		int received = 0;
		try (InputStream in = entity.getContent()) {
			while (true) {
				if (received == body.length) {
					int next = in.read();
					if (next == -1) {
						return body;
					}
					// no or a wrong Content-Length: grow by doubling, never past the limit
					if (received + 1L > maxBytes) {
						throw new BodyLimit.TooLargeException(maxBytes, received + 1L);
					}
					body = Arrays.copyOf(body, (int) Math.min(maxBytes, Math.max(received + 1L, 2L * body.length)));
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
		return find(ex, type) != null;
	}

	private static <T extends Throwable> T find(Throwable ex, Class<T> type) {
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return type.cast(t);
			}
		}
		return null;
	}

	@Override
	public void close() throws IOException {
		for (LaneClients clients : lanes.values()) {
			clients.apacheClient().close();
		}
	}

}
