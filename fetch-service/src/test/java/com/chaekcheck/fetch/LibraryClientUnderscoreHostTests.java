package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.apache.hc.client5.http.impl.InMemoryDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * The underscore host path of the fetch service (cycle 18 A2-7) against a fake library on loopback; a DNS resolver
 * of the test's own points {@code hs_lib.example.test} at it. Certificates and SNI on this path use the same
 * HttpClient settings as the main service's {@code UnderscoreHostSiteFetcher}, tested there.
 */
class LibraryClientUnderscoreHostTests {

	HttpServer library;

	final List<String> userAgents = new CopyOnWriteArrayList<>();

	final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

	LibraryClient client;

	@BeforeEach
	void start() throws IOException {
		library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		library.createContext("/page", exchange -> {
			userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
			byte[] body = "<html>1984</html>".getBytes();
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/page");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		library.createContext("/gzip", exchange -> {
			acceptEncodings.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
			byte[] body = gzip("<html>1984 gz</html>".getBytes());
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		// about 90 bytes on the wire that inflate to 64KB: over the 1KB limit of the test properties
		library.createContext("/gzip-bomb", exchange -> {
			byte[] body = gzip(new byte[64 * 1024]);
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/big", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			for (int i = 0; i < 64; i++) {
				if (!writeQuietly(exchange.getResponseBody(), new byte[512])) {
					break;
				}
			}
			exchange.close();
		});
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
		InMemoryDnsResolver dns = new InMemoryDnsResolver();
		dns.add("hs_lib.example.test", InetAddress.getByName("127.0.0.1"));
		client = new LibraryClient(UrlGuardTests.properties(true), dns);
	}

	@AfterEach
	void stop() throws IOException {
		client.close();
		library.stop(0);
	}

	@Test
	void underscoreHostIsFetchedWithTheUserAgent() {
		FetchResult result = client.get(url("/page"), "r1");

		assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.RESPONDED);
		assertThat(result.status()).isEqualTo(200);
		assertThat(result.contentType()).isEqualTo("text/html; charset=UTF-8");
		assertThat(new String(Base64.getDecoder().decode(result.body()))).isEqualTo("<html>1984</html>");
		assertThat(userAgents).containsExactly("chaekcheck-fetch/test");
	}

	@Test
	void redirectIsNotFollowed() {
		FetchResult result = client.get(url("/redirect"), "r2");

		assertThat(result.status()).isEqualTo(302);
		assertThat(result.location()).isEqualTo("/page");
		assertThat(userAgents).isEmpty();
	}

	@Test
	void bodyOverTheLimitIsBodyTooLarge() { // cycle 20: its own outcome, with the bytes read
		FetchResult result = client.get(url("/big"), "r3");

		assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.BODY_TOO_LARGE);
		assertThat(result.receivedBytes()).isGreaterThan(1024);
	}

	@Test
	void gzipIsAskedForAndInflated() { // cycle 20 (AC-34)
		FetchResult result = client.get(url("/gzip"), "r6");

		assertThat(acceptEncodings).containsExactly("gzip");
		assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.RESPONDED);
		assertThat(new String(Base64.getDecoder().decode(result.body()))).isEqualTo("<html>1984 gz</html>");
	}

	@Test
	void gzipBombIsBodyTooLarge() { // cycle 20 (AC-34): the inflated bytes pass the limit the wire bytes kept
		FetchResult result = client.get(url("/gzip-bomb"), "r7");

		assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.BODY_TOO_LARGE);
		assertThat(result.receivedBytes()).isLessThan(1024);
	}

	@Test
	void noHeadersWithinTheReadTimeoutIsATimeout() {
		assertThat(client.get(url("/slow-headers"), "r4").outcome()).isEqualTo(FetchResult.Outcome.TIMEOUT);
	}

	@Test
	void closedPortIsAConnectFailure() throws IOException {
		int port;
		try (ServerSocket socket = new ServerSocket(0)) {
			port = socket.getLocalPort();
		}
		FetchResult result = client.get(URI.create("http://hs_lib.example.test:" + port + "/page"), "r5");

		assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.CONNECT_FAILED);
	}

	private URI url(String path) {
		return URI.create("http://hs_lib.example.test:" + library.getAddress().getPort() + path);
	}

	static byte[] gzip(byte[] plain) {
		try {
			java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
			try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
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
