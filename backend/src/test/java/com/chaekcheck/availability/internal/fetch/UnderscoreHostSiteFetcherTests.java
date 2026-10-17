package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SNIMatcher;
import javax.net.ssl.SNIServerName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.StandardConstants;
import javax.net.ssl.TrustManagerFactory;

import org.apache.hc.client5.http.impl.InMemoryDnsResolver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsParameters;
import com.sun.net.httpserver.HttpsServer;

/**
 * The underscore host path (cycle 18 A2) against fake libraries on loopback. A DNS resolver of the test's own sends
 * the underscore names to 127.0.0.1. The HTTPS library has a self-signed certificate for {@code *.example.test}
 * ({@code tls/test/underscore-host-site.p12}), trusted only inside this test.
 */
class UnderscoreHostSiteFetcherTests {

	static final long MAX_BODY = 1024;

	static final char[] PASSWORD = "changeit".toCharArray();

	HttpServer library;

	HttpsServer tlsLibrary;

	final List<String> userAgents = new CopyOnWriteArrayList<>();

	final List<String> acceptEncodings = new CopyOnWriteArrayList<>();

	final List<SNIServerName> serverNamesSeen = new CopyOnWriteArrayList<>();

	UnderscoreHostSiteFetcher fetcher;

	@BeforeEach
	void start() throws Exception {
		library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		library.createContext("/fits", exchange -> {
			userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
			acceptEncodings.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
			byte[] body = new byte[(int) MAX_BODY];
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/redirect", exchange -> {
			exchange.getResponseHeaders().add("Location", "/fits");
			exchange.sendResponseHeaders(302, -1);
			exchange.close();
		});
		library.createContext("/declared-big", exchange -> {
			byte[] body = new byte[(int) MAX_BODY + 1];
			exchange.sendResponseHeaders(200, body.length);
			writeQuietly(exchange.getResponseBody(), body);
			exchange.close();
		});
		library.createContext("/gzip", exchange -> {
			byte[] body = DirectSiteFetcherTests.gzip("<html>1984 gz</html>".getBytes());
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/gzip-bomb", exchange -> {
			byte[] body = DirectSiteFetcherTests.gzip(new byte[64 * 1024]);
			exchange.getResponseHeaders().add("Content-Encoding", "gzip");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.createContext("/chunked-big", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			for (int i = 0; i < 64; i++) {
				if (!writeQuietly(exchange.getResponseBody(), new byte[512])) {
					break;
				}
			}
			exchange.close();
		});
		library.createContext("/chunked-fits", exchange -> {
			exchange.sendResponseHeaders(200, 0);
			for (int i = 0; i < 2; i++) {
				writeQuietly(exchange.getResponseBody(), new byte[512]);
			}
			exchange.close();
		});
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

		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream in = getClass().getResourceAsStream("/tls/test/underscore-host-site.p12")) {
			keys.load(in, PASSWORD);
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, PASSWORD);
		SSLContext serverTls = SSLContext.getInstance("TLS");
		serverTls.init(keyManagers.getKeyManagers(), null, null);
		tlsLibrary = HttpsServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		tlsLibrary.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		tlsLibrary.setHttpsConfigurator(new HttpsConfigurator(serverTls) {
			@Override
			public void configure(HttpsParameters params) {
				SSLParameters ssl = getSSLContext().getDefaultSSLParameters();
				// called only when the client sends a server name
				ssl.setSNIMatchers(List.of(new SNIMatcher(StandardConstants.SNI_HOST_NAME) {
					@Override
					public boolean matches(SNIServerName serverName) {
						serverNamesSeen.add(serverName);
						return true;
					}
				}));
				params.setSSLParameters(ssl);
			}
		});
		tlsLibrary.createContext("/", exchange -> {
			byte[] body = "ok".getBytes();
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		tlsLibrary.start();

		TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(keys);
		SSLContext clientTls = SSLContext.getInstance("TLS");
		clientTls.init(null, trust.getTrustManagers(), null);
		InMemoryDnsResolver dns = new InMemoryDnsResolver();
		InetAddress loopback = InetAddress.getByName("127.0.0.1");
		for (String name : List.of("hs_lib.example.test", "hs_site.example.test", "site.example.test",
				"hs_site.other.test")) {
			dns.add(name, loopback);
		}
		fetcher = UnderscoreHostSiteFetcher.create(clientTls, Duration.ofSeconds(1), Duration.ofMillis(500),
				Duration.ofSeconds(1), MAX_BODY, "chaekcheck/test", Clock.systemUTC(), dns);
	}

	@AfterEach
	void stop() throws IOException {
		fetcher.close();
		library.stop(0);
		tlsLibrary.stop(0);
	}

	@Test
	void underscoreHostIsRequestedWithTheUserAgent() {
		SiteResponse response = fetcher.fetch(url("/fits"), Instant.MAX, EgressLane.DIRECT);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).hasSize((int) MAX_BODY);
		assertThat(response.contentType()).isEqualTo("text/html; charset=UTF-8");
		assertThat(response.via()).as("cycle 22: via is the lane id").isEqualTo(EgressLane.DIRECT_ID);
		assertThat(userAgents).containsExactly("chaekcheck/test");
		// cycle 20: gzip is asked for on this path too (the client's own decoding stays off, ContentEncoding inflates)
		assertThat(acceptEncodings).containsExactly("gzip");
	}

	@Test
	void gzipAnswerIsInflatedAndABombIsTooLarge() { // cycle 20 (AC-34)
		SiteResponse gz = fetcher.fetch(url("/gzip"), Instant.MAX, EgressLane.DIRECT);
		assertThat(gz.body()).isEqualTo("<html>1984 gz</html>".getBytes());

		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/gzip-bomb"), Instant.MAX, EgressLane.DIRECT));
		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.bodyTooLarge()).isTrue();
		assertThat(ex.receivedBytes()).isLessThan(MAX_BODY);
	}

	@Test
	void redirectIsNotFollowed() {
		SiteResponse response = fetcher.fetch(url("/redirect"), Instant.MAX, EgressLane.DIRECT);

		assertThat(response.status()).isEqualTo(302);
		assertThat(response.location()).isEqualTo("/fits");
		assertThat(userAgents).isEmpty();
	}

	@Test
	void chunkedBodyWithinTheLimitIsReturned() {
		assertThat(fetcher.fetch(url("/chunked-fits"), Instant.MAX, EgressLane.DIRECT).body()).hasSize(1024);
	}

	@Test
	void bodyDeclaredOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/declared-big"), Instant.MAX, EgressLane.DIRECT));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.getMessage()).contains("over 1024 bytes");
	}

	@Test
	void chunkedBodyOverTheLimitIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/chunked-big"), Instant.MAX, EgressLane.DIRECT));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.getMessage()).contains("over 1024 bytes");
	}

	@Test
	void slowBodyHitsTheTotalTimeLimit() {
		long start = System.nanoTime();
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/trickle"), Instant.MAX, EgressLane.DIRECT));
		long tookMillis = (System.nanoTime() - start) / 1_000_000;

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
		assertThat(ex.getMessage()).contains("total time limit");
		assertThat(tookMillis).isBetween(900L, 2500L);
	}

	@Test
	void noHeadersWithinTheReadTimeoutIsATimeout() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(url("/slow-headers"), Instant.MAX, EgressLane.DIRECT));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT);
	}

	@Test
	void certificateForTheNameIsAcceptedAndNoServerNameIsSent() {
		SiteResponse response = fetcher.fetch(tlsUrl("hs_site.example.test"), Instant.MAX, EgressLane.DIRECT);

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.body()).asString().isEqualTo("ok");
		assertThat(serverNamesSeen).isEmpty();
	}

	@Test
	void serverNameCheckInThisTestSeesANameWhenOneIsSent() {
		// control for the test above: a name without an underscore does go out as SNI
		fetcher.fetch(tlsUrl("site.example.test"), Instant.MAX, EgressLane.DIRECT);

		assertThat(serverNamesSeen).hasSize(1);
	}

	@Test
	void certificateForAnotherNameIsAnUpstreamError() {
		UpstreamException ex = catchThrowableOfType(UpstreamException.class,
				() -> fetcher.fetch(tlsUrl("hs_site.other.test"), Instant.MAX, EgressLane.DIRECT));

		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.getMessage()).contains("hs_site.other.test");
	}

	private URI url(String path) {
		return URI.create("http://hs_lib.example.test:" + library.getAddress().getPort() + path);
	}

	private URI tlsUrl(String host) {
		return URI.create("https://" + host + ":" + tlsLibrary.getAddress().getPort() + "/");
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
