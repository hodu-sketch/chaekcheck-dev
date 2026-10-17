package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.availability.internal.egress.BlockSignal;
import com.chaekcheck.availability.internal.egress.EgressLane;
import com.chaekcheck.availability.internal.egress.EgressLanes;
import com.chaekcheck.availability.internal.egress.EgressMode;
import com.chaekcheck.availability.internal.egress.HostRules;
import com.chaekcheck.availability.internal.egress.InMemoryBlockedLanes;
import com.chaekcheck.availability.internal.egress.InMemoryLaneThrottle;
import com.chaekcheck.availability.internal.egress.LaneCounters;
import com.chaekcheck.availability.internal.egress.TestHttpProxy;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Requests really leave through the lane's proxy (17 AC-8 / R13-1, handoff 1-15 tests): a fake library on loopback,
 * two {@link TestHttpProxy} instances as proxy lanes, the real {@link LaneSiteFetcher} and {@link EgressLanes}. Also
 * the proxy credentials (user decision 2026-10-17: the local PC uses Webshare's username / password): every proxy
 * lane is one Apache client that sends them preemptively, for http, the https CONNECT tunnel and underscore host
 * names, because Webshare answers an unauthenticated CONNECT with 407 and no challenge (found 2026-10-18).
 */
@ExtendWith(OutputCaptureExtension.class)
class LaneSiteFetcherProxyTests {

	static final char[] PASSWORD = "changeit".toCharArray();

	HttpServer library;

	HttpsServer tlsLibrary;

	SSLContext clientTls;

	final List<AutoCloseable> closing = new ArrayList<>();

	@BeforeEach
	void start() throws Exception {
		library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		library.createContext("/search", exchange -> {
			byte[] body = "<p>ok</p>".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
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
		tlsLibrary.setHttpsConfigurator(new HttpsConfigurator(serverTls));
		tlsLibrary.createContext("/", exchange -> {
			byte[] body = "tls ok".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		tlsLibrary.start();
		TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		trust.init(keys);
		clientTls = SSLContext.getInstance("TLS");
		clientTls.init(null, trust.getTrustManagers(), null);
	}

	@AfterEach
	void stop() throws Exception {
		for (AutoCloseable c : closing) {
			c.close();
		}
		library.stop(0);
		tlsLibrary.stop(0);
	}

	@Test
	void requestsAlternateBetweenTheDirectLaneAndTwoProxies(CapturedOutput output) throws Exception { // 17 AC-8
		TestHttpProxy proxy1 = proxy(library.getAddress(), null, null);
		TestHttpProxy proxy2 = proxy(library.getAddress(), null, null);
		List<EgressLane> laneList = EgressLane.list(proxy1.hostPort() + "," + proxy2.hostPort(), "webshare");
		EgressLanes lanes = lanes(laneList, EgressMode.ALL, null);
		URI uri = URI.create("http://127.0.0.1:" + library.getAddress().getPort() + "/search?q=x");

		List<String> vias = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			SiteResponse response = lanes.fetch(uri, Instant.MAX);
			assertThat(response.status()).isEqualTo(200);
			assertThat(new String(response.body(), StandardCharsets.UTF_8)).isEqualTo("<p>ok</p>");
			vias.add(response.via());
		}

		assertThat(vias).containsExactly("direct", "proxy-01", "proxy-02", "direct", "proxy-01", "proxy-02");
		assertThat(proxy1.served).hasSize(2).allMatch(line -> line.startsWith("GET http://127.0.0.1:"));
		assertThat(proxy2.served).hasSize(2);
		assertThat(output.getOut()).contains("upstream via=proxy-01 lane=proxy-01 vendor=webshare")
			.contains("upstream via=proxy-02 lane=proxy-02 vendor=webshare")
			.contains("upstream via=direct lane=direct vendor=direct")
			.doesNotContain(proxy1.hostPort())
			.doesNotContain(proxy2.hostPort());
	}

	@Test
	void proxyOnlySendsNothingDirectly() throws Exception {
		TestHttpProxy proxy1 = proxy(library.getAddress(), null, null);
		EgressLanes lanes = lanes(EgressLane.list(proxy1.hostPort(), "webshare"), EgressMode.PROXY_ONLY, null);
		URI uri = URI.create("http://127.0.0.1:" + library.getAddress().getPort() + "/search?q=x");

		for (int i = 0; i < 3; i++) {
			assertThat(lanes.fetch(uri, Instant.MAX).via()).isEqualTo("proxy-01");
		}
		assertThat(proxy1.served).hasSize(3);
	}

	@Test
	void proxyCredentialsAnswerTheProxysChallengeOnHttpHttpsAndUnderscoreHosts() throws Exception {
		TestHttpProxy authProxy = proxy(null, "user", "pw");
		List<EgressLane> laneList = EgressLane.list(authProxy.hostPort(), "webshare");
		URI http = URI.create("http://127.0.0.1:" + library.getAddress().getPort() + "/search?q=x");
		// the proxy connects to the host the request names: the TLS library is 127.0.0.1 too, but the certificate
		// is for *.example.test, so the request names that host and the proxy is told where it really is
		TestHttpProxy tlsProxy = proxy(tlsLibrary.getAddress(), "user", "pw");
		URI https = URI.create("https://site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
		URI underscore = URI.create("https://hs_site.example.test:" + tlsLibrary.getAddress().getPort() + "/");

		// without credentials the proxy's 407 is the answer
		EgressLanes noCredentials = lanes(laneList, EgressMode.PROXY_ONLY, null);
		assertThat(noCredentials.fetch(http, Instant.MAX).status()).isEqualTo(407);
		assertThat(authProxy.refused.get()).isEqualTo(1);

		EgressLanes withCredentials = lanes(laneList, EgressMode.PROXY_ONLY, new String[] { "user", "pw" });
		SiteResponse response = withCredentials.fetch(http, Instant.MAX);
		assertThat(response.status()).isEqualTo(200);
		assertThat(response.via()).isEqualTo("proxy-01");
		assertThat(authProxy.served).hasSize(1);

		EgressLanes tlsLanes = lanes(EgressLane.list(tlsProxy.hostPort(), "webshare"), EgressMode.PROXY_ONLY,
				new String[] { "user", "pw" });
		SiteResponse tunneled = tlsLanes.fetch(https, Instant.MAX);
		assertThat(tunneled.status()).as("JDK client: CONNECT with Basic").isEqualTo(200);
		assertThat(new String(tunneled.body(), StandardCharsets.UTF_8)).isEqualTo("tls ok");
		SiteResponse apache = tlsLanes.fetch(underscore, Instant.MAX);
		assertThat(apache.status()).as("Apache client (underscore host): CONNECT with Basic").isEqualTo(200);
		assertThat(tlsProxy.served).hasSize(2).allMatch(line -> line.startsWith("CONNECT "));
		assertThat(tlsProxy.served.get(1)).startsWith("CONNECT hs_site.example.test:");
		// a challenging proxy needs no second round trip either: the credentials were on the first request
		assertThat(tlsProxy.refused.get()).isZero();
	}

	@Test
	void credentialsGoOutOnTheFirstRequestSoAWebshareLikeProxyWithoutAChallengeAccepts(CapturedOutput output)
			throws Exception {
		// Webshare (curl, 2026-10-18): unauthenticated CONNECT -> 407, no Proxy-Authenticate, connection closed
		TestHttpProxy httpProxy = proxy(null, "user", "pw").webshareLike();
		TestHttpProxy tlsProxy = proxy(tlsLibrary.getAddress(), "user", "pw").webshareLike();
		URI http = URI.create("http://127.0.0.1:" + library.getAddress().getPort() + "/search?q=x");
		URI https = URI.create("https://site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
		URI underscore = URI.create("https://hs_site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
		String[] credentials = { "user", "pw" };

		SiteResponse plain = lanes(EgressLane.list(httpProxy.hostPort(), "webshare"), EgressMode.PROXY_ONLY, credentials)
			.fetch(http, Instant.MAX);
		assertThat(plain.status()).as("http through the proxy, first try").isEqualTo(200);
		assertThat(plain.via()).isEqualTo("proxy-01");
		assertThat(httpProxy.refused.get()).isZero();
		assertThat(httpProxy.served).hasSize(1).first().asString().startsWith("GET http://127.0.0.1:");

		EgressLanes tlsLanes = lanes(EgressLane.list(tlsProxy.hostPort(), "webshare"), EgressMode.PROXY_ONLY,
				credentials);
		SiteResponse tunneled = tlsLanes.fetch(https, Instant.MAX);
		assertThat(tunneled.status()).as("https: the first CONNECT carries Proxy-Authorization").isEqualTo(200);
		assertThat(new String(tunneled.body(), StandardCharsets.UTF_8)).isEqualTo("tls ok");
		assertThat(tlsLanes.fetch(underscore, Instant.MAX).status()).as("underscore host, same client").isEqualTo(200);
		assertThat(tlsProxy.refused.get()).isZero();
		assertThat(tlsProxy.served).hasSize(2).allMatch(line -> line.startsWith("CONNECT "));
		assertThat(tlsProxy.served.get(1)).startsWith("CONNECT hs_site.example.test:");

		// the log line keeps the lane fields and bytes=, and never the proxy address
		assertThat(output.getOut()).contains("upstream via=proxy-01 lane=proxy-01 vendor=webshare host=site.example.test:")
			.contains("status=200 bytes=6 ")
			.doesNotContain(tlsProxy.hostPort())
			.doesNotContain(httpProxy.hostPort())
			.doesNotContain("user:pw")
			.doesNotContain("dXNlcjpwdw==");
	}

	@Test
	void withoutOrWithWrongCredentialsTheProxys407IsTheAnswerAndABlockSignal() throws Exception {
		TestHttpProxy tlsProxy = proxy(tlsLibrary.getAddress(), "user", "pw").webshareLike();
		List<EgressLane> laneList = EgressLane.list(tlsProxy.hostPort(), "webshare");
		URI http = URI.create("http://127.0.0.1:" + library.getAddress().getPort() + "/search?q=x");
		URI https = URI.create("https://site.example.test:" + tlsLibrary.getAddress().getPort() + "/");

		// no credentials (the production setting): the lane behaves as before, the proxy's 407 is the answer
		EgressLanes noCredentials = lanes(laneList, EgressMode.PROXY_ONLY, null);
		SiteResponse plain = noCredentials.fetch(http, Instant.MAX);
		assertThat(plain.status()).isEqualTo(407);
		assertThat(BlockSignal.of(plain)).isTrue();
		SiteResponse tunnel = noCredentials.fetch(https, Instant.MAX);
		assertThat(tunnel.status()).as("a refused CONNECT is reported as the proxy's status").isEqualTo(407);
		assertThat(tunnel.via()).isEqualTo("proxy-01");
		assertThat(BlockSignal.of(tunnel)).isTrue();

		EgressLanes wrong = lanes(laneList, EgressMode.PROXY_ONLY, new String[] { "user", "wrong" });
		assertThat(wrong.fetch(http, Instant.MAX).status()).isEqualTo(407);
		assertThat(wrong.fetch(https, Instant.MAX).status()).isEqualTo(407);
		assertThat(tlsProxy.refused.get()).isEqualTo(4);
		assertThat(tlsProxy.served).isEmpty();
	}

	private TestHttpProxy proxy(InetSocketAddress target, String username, String password) throws IOException {
		TestHttpProxy proxy = new TestHttpProxy(target, username, password);
		closing.add(proxy);
		return proxy;
	}

	private EgressLanes lanes(List<EgressLane> laneList, EgressMode mode, String[] credentials) {
		LaneSiteFetcher fetcher = new LaneSiteFetcher(laneList, clientTls, Duration.ofSeconds(2), Duration.ofSeconds(2),
				Duration.ofSeconds(5), 1024 * 1024, "chaekcheck/test", credentials, Clock.systemUTC());
		closing.add(fetcher);
		return new EgressLanes(mode, laneList, new HostRules(1, Duration.ZERO, 3, ".dkyobobook.co.kr"),
				new InMemoryLaneThrottle(Clock.systemUTC()),
				new InMemoryBlockedLanes(5, Duration.ofMinutes(10), Duration.ofDays(7)), fetcher,
				new LaneCounters(laneList), Clock.systemUTC());
	}

}
