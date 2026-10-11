package com.chaekcheck.popular.internal.fetch;

import java.io.IOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
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
 * {@link java.net.http.HttpClient} with its own cookie store per fetch (the site's {@code JSESSIONID}, Path=/home),
 * the connect time-out, a read time-out per request (until the headers), redirects not followed (a 302 to
 * accessDenied must be seen as {@code request_rejected}) and the User-Agent on every request. The whole request,
 * body included, may take connect + read time-out.
 */
public final class HttpKpipaTransport implements KpipaTransport {

	private final URI baseUrl;

	private final Duration connectTimeout;

	private final Duration readTimeout;

	private final long maxBodyBytes;

	private final long maxCoverBytes;

	private final String userAgent;

	/**
	 * @param maxBodyBytes largest page or POST answer read
	 * @param maxCoverBytes largest cover image read
	 */
	public HttpKpipaTransport(URI baseUrl, Duration connectTimeout, Duration readTimeout, long maxBodyBytes,
			long maxCoverBytes, String userAgent) {
		this.baseUrl = baseUrl;
		this.connectTimeout = connectTimeout;
		this.readTimeout = readTimeout;
		this.maxBodyBytes = maxBodyBytes;
		this.maxCoverBytes = maxCoverBytes;
		this.userAgent = userAgent;
	}

	@Override
	public Connection open() {
		HttpClient client = HttpClient.newBuilder()
			.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
			.connectTimeout(connectTimeout)
			.followRedirects(HttpClient.Redirect.NEVER)
			.build();
		return new HttpConnection(client);
	}

	private final class HttpConnection implements Connection {

		private final HttpClient client;

		HttpConnection(HttpClient client) {
			this.client = client;
		}

		@Override
		public KpipaResponse send(KpipaRequest request) throws IOException, InterruptedException {
			HttpRequest.Builder builder = HttpRequest.newBuilder(baseUrl.resolve(request.path()))
				.timeout(readTimeout)
				.header("User-Agent", userAgent);
			request.headers().forEach(builder::header);
			builder.method(request.method(), request.form() == null ? HttpRequest.BodyPublishers.noBody()
					: HttpRequest.BodyPublishers.ofString(request.form()));
			Duration total = connectTimeout.plus(readTimeout);
			CompletableFuture<HttpResponse<byte[]>> call = client.sendAsync(builder.build(),
					BodyLimit.handler(request.cover() ? maxCoverBytes : maxBodyBytes));
			try {
				HttpResponse<byte[]> response = call.get(total.toMillis(), TimeUnit.MILLISECONDS);
				return new KpipaResponse(response.statusCode(),
						response.headers().firstValue("Content-Type").orElse(null), response.body());
			}
			catch (TimeoutException ex) {
				call.cancel(true);
				throw new HttpTimeoutException("request passed " + total.toMillis() + "ms");
			}
			catch (ExecutionException ex) {
				if (ex.getCause() instanceof IOException io) {
					throw io;
				}
				throw new IOException(ex.getCause());
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

}
