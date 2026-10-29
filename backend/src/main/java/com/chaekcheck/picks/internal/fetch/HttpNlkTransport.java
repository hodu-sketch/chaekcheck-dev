package com.chaekcheck.picks.internal.fetch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * {@link java.net.http.HttpClient} with the connect time-out, a read time-out per request (until the headers),
 * redirects not followed ({@code followRedirects(NEVER)}: a 3xx is {@code request_rejected} for the list, ADR-0091) and the User-Agent on every request. The whole request, body included, may take connect + read
 * time-out. Exceptions thrown from here never carry the request URI: it holds the API key (spec R1-7).
 */
public final class HttpNlkTransport implements NlkTransport, AutoCloseable {

	private final HttpClient client;

	private final Duration connectTimeout;

	private final Duration readTimeout;

	private final String userAgent;

	public HttpNlkTransport(Duration connectTimeout, Duration readTimeout, String userAgent) {
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.userAgent = userAgent;
		this.client = HttpClient.newBuilder()
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
	}

	@Override
	public NlkResponse get(URI uri, long maxBodyBytes) throws IOException, InterruptedException {
		HttpRequest request = HttpRequest.newBuilder(uri).timeout(readTimeout).header("User-Agent", userAgent).GET().build();
		Duration total = connectTimeout.plus(readTimeout);
		CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(request, BodyLimit.handler(maxBodyBytes));
		try {
			HttpResponse<byte[]> response = call.get(total.toMillis(), TimeUnit.MILLISECONDS);
			return new NlkResponse(response.statusCode(), response.headers().firstValue("Content-Type").orElse(null),
					response.body());
		}
		catch (TimeoutException ex) {
			call.cancel(true);
			throw new HttpTimeoutException("request passed " + total.toMillis() + "ms");
		}
		catch (ExecutionException ex) {
			if (ex.getCause() instanceof BodyTooLargeException tooLarge) {
				throw tooLarge;
			}
			if (ex.getCause() instanceof HttpTimeoutException timeout) {
				throw new HttpTimeoutException(timeout.getClass().getSimpleName());
			}
			// the JDK's messages may name the address; keep only the kind of failure
			throw new IOException(ex.getCause() == null ? "request failed" : ex.getCause().getClass().getSimpleName());
		}
		catch (InterruptedException ex) {
			call.cancel(true);
			throw ex;
		}
	}

	@Override
	public void close() {
		client.close();
	}

}
