package com.chaekcheck.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.SSLContext;

import org.apache.hc.client5.http.ConnectTimeoutException;
import org.apache.hc.client5.http.DnsResolver;
import org.apache.hc.client5.http.SystemDefaultDnsResolver;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.client5.http.ssl.ClientTlsStrategyBuilder;
import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.apache.hc.client5.http.ssl.HostnameVerificationPolicy;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * GETs a library page: GET only, redirects not followed, fixed User-Agent. Limits (application.yml {@code fetch.*}):
 * connect timeout, read timeout (until the response headers), a total time limit for the whole request including
 * the body, and a maximum body size; a bigger body stops being read and the call fails as ERROR. No cache and no host
 * throttling (those live in the main service, ADR-0011). Every call is logged with host, path, start, end, status and
 * User-Agent.
 * <p>
 * Cycle 18 (ADR-0111): a host name with an underscore goes through Apache HttpClient 5 instead (java.net.http refuses
 * it), with the same limits, outcomes and log line ({@link #getUnderscoreHost}).
 */
@Component
class LibraryClient implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(LibraryClient.class);

	private final HttpClient httpClient;

	private final CloseableHttpClient underscoreClient;

	private final FetchProperties properties;

	private final Clock clock;

	@Autowired
	LibraryClient(FetchProperties properties) {
		this(properties, SystemDefaultDnsResolver.INSTANCE);
	}

	/** With a DNS resolver of its own for the underscore path, for tests that point such a name at a local server. */
	LibraryClient(FetchProperties properties, DnsResolver underscoreDns) {
		this.properties = properties;
		this.clock = Clock.systemUTC();
		SSLContext sslContext = LibrarySiteTls.sslContext();
		this.httpClient = HttpClient.newBuilder()
			.sslContext(sslContext)
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		this.underscoreClient = underscoreClient(properties, sslContext, underscoreDns);
	}

	/**
	 * Apache HttpClient 5 for host names with an underscore (cycle 18, ADR-0111), with the same limits and TLS as the
	 * JDK client. The certificate's host name is checked by HttpClient's own verifier (the JDK's built-in check
	 * refuses underscore names); no SNI goes out because the JDK leaves out a server name that is not a valid
	 * SNIHostName. No redirects, retries, cookies or content compression.
	 */
	private static CloseableHttpClient underscoreClient(FetchProperties properties, SSLContext sslContext,
			DnsResolver dns) {
		ClientTlsStrategyBuilder tls = ClientTlsStrategyBuilder.create()
			.setSslContext(sslContext)
			.setHostVerificationPolicy(HostnameVerificationPolicy.CLIENT)
			.setHostnameVerifier(new DefaultHostnameVerifier());
		return HttpClients.custom()
			.setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
				.setTlsSocketStrategy(tls.buildClassic())
				.setDnsResolver(dns)
				.setDefaultConnectionConfig(ConnectionConfig.custom()
					.setConnectTimeout(Timeout.of(properties.connectTimeout()))
					.setSocketTimeout(Timeout.of(properties.readTimeout()))
					.build())
				.build())
			.setDefaultRequestConfig(
					RequestConfig.custom().setResponseTimeout(Timeout.of(properties.readTimeout())).build())
			.disableRedirectHandling()
			.disableAutomaticRetries()
			.disableCookieManagement()
			.disableContentCompression()
			.disableAuthCaching()
			.setUserAgent(properties.userAgent())
			.build();
	}

	FetchResult get(URI uri, String requestId) {
		if (UnderscoreHosts.isUnderscoreHost(uri)) {
			return getUnderscoreHost(uri, requestId);
		}
		Instant start = clock.instant();
		String status = "-";
		CompletableFuture<HttpResponse<byte[]>> call = null;
		try {
			HttpRequest request;
			try {
				request = HttpRequest.newBuilder(uri)
					.timeout(properties.readTimeout())
					.header("User-Agent", properties.userAgent())
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
			return new FetchResult(FetchResult.Outcome.RESPONDED, response.statusCode(),
					response.headers().firstValue("Content-Type").orElse(null),
					response.headers().firstValue("Location").orElse(null),
					Base64.getEncoder().encodeToString(response.body()), start, clock.instant());
		}
		catch (TimeoutException ex) {
			// the whole request, body included, passed the total time limit
			call.cancel(true);
			status = "total-timeout";
			return FetchResult.failed(FetchResult.Outcome.TIMEOUT, start, clock.instant());
		}
		catch (ExecutionException ex) {
			Throwable cause = ex.getCause();
			if (has(cause, BodyLimit.TooLargeException.class)) {
				status = "body-too-large";
				return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
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
			log.info("library host={} path={} requestId={} start={} end={} status={} userAgent=\"{}\"",
					UnderscoreHosts.host(uri), uri.getRawPath(), requestId, start, clock.instant(), status,
					properties.userAgent());
		}
	}

	/** Same limits, outcomes and log line as {@link #get}, through {@link #underscoreClient}. */
	private FetchResult getUnderscoreHost(URI uri, String requestId) {
		Instant start = clock.instant();
		String status = "-";
		HttpGet get = new HttpGet(uri);
		get.setHeader("User-Agent", properties.userAgent());
		FutureTask<FetchResult> call = new FutureTask<>(() -> underscoreClient.execute(get, response -> {
			byte[] body = body(response.getEntity(), properties.maxBodySize().toBytes());
			return new FetchResult(FetchResult.Outcome.RESPONDED, response.getCode(),
					value(response.getFirstHeader("Content-Type")), value(response.getFirstHeader("Location")),
					Base64.getEncoder().encodeToString(body), start, clock.instant());
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
			if (has(cause, BodyLimit.TooLargeException.class)) {
				status = "body-too-large";
				return FetchResult.failed(FetchResult.Outcome.ERROR, start, clock.instant());
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
			log.info("library host={} path={} requestId={} start={} end={} status={} userAgent=\"{}\"",
					UnderscoreHosts.host(uri), uri.getRawPath(), requestId, start, clock.instant(), status,
					properties.userAgent());
		}
	}

	/** The body, at most {@code maxBytes}; more (declared or read) fails like {@link BodyLimit}. */
	private static byte[] body(HttpEntity entity, long maxBytes) throws IOException {
		if (entity == null) {
			return new byte[0];
		}
		long declared = entity.getContentLength();
		if (declared > maxBytes) {
			throw new BodyLimit.TooLargeException(maxBytes);
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
						throw new BodyLimit.TooLargeException(maxBytes);
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
		for (Throwable t = ex; t != null; t = t.getCause()) {
			if (type.isInstance(t)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public void close() throws IOException {
		underscoreClient.close();
	}

}
