package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.Charset;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;
import com.sun.net.httpserver.HttpServer;

/**
 * Body size and total time limits of the direct mode, against a fake library on loopback. Cycle 20: gzip is asked
 * for and inflated, and the limit holds for the inflated bytes too (AC-34).
 */
class DirectSiteFetcherTests {

	static final long MAX_BODY = 1024;

	HttpServer library;

	final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

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
			acceptEncodings.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
			byte[] body = new byte[(int) MAX_BODY];
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		// cycle 20: a normal gzip page (EUC-KR bytes stay bytes), one that inflates to exactly the limit, a bomb
		// (about 90 bytes on the wire, 64KB inflated), and an encoding this service did not ask for
		library.createContext("/gzip", exchange -> {
			byte[] body = gzip(EUC_KR_PAGE);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=EUC-KR");
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/gzip-fits", exchange -> {
			byte[] body = gzip(new byte[(int) MAX_BODY]);
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/gzip-bomb", exchange -> {
			byte[] body = gzip(new byte[64 * 1024]);
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/brotli", exchange -> {
			byte[] body = new byte[16];
			exchange.getResponseHeaders().add("Content-Encoding", "br");
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
	void bodyAtTheLimitIsReturnedAndGzipIsAskedFor() { // AC-34: the request carries Accept-Encoding: gzip
		SiteResponse response = fetcher.fetch(url("/fits"), Instant.MAX);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).hasSize((int) MAX_BODY);
		assertThat(response.contentType()).isEqualTo("text/html; charset=UTF-8");
		assertThat(acceptEncodings).containsExactly("gzip");
	}

	@Test
	void gzipAnswerIsInflatedToTheSitesBytes() { // AC-34: same bytes as a plain answer, EUC-KR untouched
		SiteResponse response = fetcher.fetch(url("/gzip"), Instant.MAX);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).isEqualTo(EUC_KR_PAGE);
		assertThat(response.declaredCharset()).isEqualTo(Charset.forName("EUC-KR"));
		assertThat(fetcher.fetch(url("/gzip-fits"), Instant.MAX).body()).hasSize((int) MAX_BODY);
	}

	@Test
	void gzipBombIsBodyTooLargeWithoutHoldingTheInflatedBytes() { // AC-34: compression bomb
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/gzip-bomb"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.bodyTooLarge()).isTrue();
		assertThat(ex.uri()).isEqualTo(url("/gzip-bomb"));
		// what came over the wire is small; the inflater stopped right past the limit (ContentEncodingTests)
		assertThat(ex.receivedBytes()).isPositive().isLessThan(MAX_BODY);
	}

	@Test
	void encodingNotAskedForFailsThatLibraryAlone() { // [안]: only gzip is inflated
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/brotli"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.bodyTooLarge()).isFalse();
		assertThat(ex.getMessage()).contains("Content-Encoding");
	}

	@Test
	void bodyDeclaredOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/declared-big"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.bodyTooLarge()).isTrue();
		assertThat(ex.receivedBytes()).isEqualTo(MAX_BODY + 1);
	}

	@Test
	void chunkedBodyOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/chunked-big"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.bodyTooLarge()).isTrue();
		assertThat(ex.getMessage()).contains("over 1024 bytes");
		assertThat(ex.receivedBytes()).isGreaterThan(MAX_BODY);
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

	@Test
	void addressJavaNetHttpRefusesIsAnUpstreamErrorForThatRowOnly() { // cycle 18: no IllegalArgumentException
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(URI.create("https://hs_partners.yes24library.com/"), Instant.MAX));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.getMessage()).contains("hs_partners.yes24library.com");
	}

	private URI url(String path) {
		return URI.create("http://127.0.0.1:" + library.getAddress().getPort() + path);
	}

	static final byte[] EUC_KR_PAGE = "<html><p>채식주의자</p></html>".getBytes(Charset.forName("EUC-KR"));

	static byte[] gzip(byte[] plain) {
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
				gz.write(plain);
			}
			return out.toByteArray();
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
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
