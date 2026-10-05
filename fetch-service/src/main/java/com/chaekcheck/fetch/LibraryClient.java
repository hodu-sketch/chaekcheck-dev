package com.chaekcheck.fetch;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * GETs a library page: GET only, redirects not followed, fixed User-Agent. Limits (application.yml {@code fetch.*}):
 * connect timeout, read timeout (until the response headers), a total time limit for the whole request including
 * the body, and a maximum body size; a bigger body stops being read and the call fails as ERROR. No cache and no host
 * throttling (those live in the main service, ADR-0011). Every call is logged with host, path, start, end, status and
 * User-Agent.
 */
@Component
class LibraryClient {

	private static final Logger log = LoggerFactory.getLogger(LibraryClient.class);

	private final HttpClient httpClient;

	private final FetchProperties properties;

	private final Clock clock;

	LibraryClient(FetchProperties properties) {
		this.properties = properties;
		this.clock = Clock.systemUTC();
		this.httpClient = HttpClient.newBuilder()
			.sslContext(LibrarySiteTls.sslContext())
			.connectTimeout(properties.connectTimeout())
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	FetchResult get(URI uri, String requestId) {
		Instant start = clock.instant();
		String status = "-";
		CompletableFuture<HttpResponse<byte[]>> call = null;
		try {
			HttpRequest request = HttpRequest.newBuilder(uri)
				.timeout(properties.readTimeout())
				.header("User-Agent", properties.userAgent())
				.GET()
				.build();
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
			log.info("library host={} path={} requestId={} start={} end={} status={} userAgent=\"{}\"", uri.getHost(),
					uri.getRawPath(), requestId, start, clock.instant(), status, properties.userAgent());
		}
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
