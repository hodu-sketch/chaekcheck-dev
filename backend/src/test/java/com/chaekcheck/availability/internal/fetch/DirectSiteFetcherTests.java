package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;
import com.sun.net.httpserver.HttpServer;

/** Body size and total time limits of the direct mode, against a fake library on loopback. */
class DirectSiteFetcherTests {

	static final long MAX_BODY = 1024;

	HttpServer library;

	DirectSiteFetcher fetcher = new DirectSiteFetcher(
			HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(1))
				.followRedirects(HttpClient.Redirect.NEVER)
				.build(),
			Duration.ofMillis(500), Duration.ofSeconds(1), MAX_BODY, "chaekcheck/test", Clock.systemUTC());

	@BeforeEach
	void start() throws IOException {
		library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		library.createContext("/fits", exchange -> {
			byte[] body = new byte[(int) MAX_BODY];
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		// Content-Length says too big: refused before the body is read
		library.createContext("/declared-big", exchange -> {
			byte[] body = new byte[(int) MAX_BODY + 1];
			exchange.sendResponseHeaders(200, body.length);
			writeQuietly(exchange.getResponseBody(), body);
			exchange.close();
		});
		// chunked, no Content-Length: stopped once the counted bytes pass the limit
		library.createContext("/chunked-big", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			for (int i = 0; i < 64; i++) {
				if (!writeQuietly(exchange.getResponseBody(), new byte[512])) {
					break;
				}
			}
			exchange.close();
		});
		// headers at once (inside the read timeout), then a few bytes every 200ms for 3s
		library.createContext("/trickle", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			for (int i = 0; i < 15; i++) {
				try {
					Thread.sleep(200);
				}
				catch (InterruptedException ex) {
					break;
				}
				if (!writeQuietly(exchange.getResponseBody(), new byte[8])) {
					break;
				}
			}
			exchange.close();
		});
		// no headers within the read timeout
		library.createContext("/slow-headers", exchange -> {
			try {
				Thread.sleep(1500);
				exchange.sendResponseHeaders(200, -1);
			}
			catch (InterruptedException | IOException ignored) {
				// the client gave up
			}
			exchange.close();
		});
		library.start();
	}

	@AfterEach
	void stop() {
		library.stop(0);
	}

	@Test
	void bodyAtTheLimitIsReturned() {
		SiteResponse response = fetcher.fetch(url("/fits"), Instant.MAX);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).hasSize((int) MAX_BODY);
		assertThat(response.contentType()).isEqualTo("text/html; charset=UTF-8");
	}

	@Test
	void bodyDeclaredOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/declared-big"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
	}

	@Test
	void chunkedBodyOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/chunked-big"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.getMessage()).contains("over 1024 bytes");
	}

	@Test
	void slowBodyHitsTheTotalTimeLimit() {
		long start = System.nanoTime();
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/trickle"), Instant.MAX));
		long tookMillis = (System.nanoTime() - start) / 1_000_000;

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
		assertThat(ex.getMessage()).contains("total time limit");
		// the body would take 3s; the 1s total limit stops it
		assertThat(tookMillis).isBetween(900L, 2500L);
	}

	@Test
	void noHeadersWithinTheReadTimeoutIsStillATimeout() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/slow-headers"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
	}

	private URI url(String path) {
		return URI.create("http://127.0.0.1:" + library.getAddress().getPort() + path);
	}

	/** @return false once the client has gone */
	private static boolean writeQuietly(OutputStream out, byte[] bytes) {
		try {
			out.write(bytes);
			out.flush();
			return true;
		}
		catch (IOException ex) {
			return false;
		}
	}

}
