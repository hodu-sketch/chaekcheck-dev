package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.concurrent.Executors;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

import org.apache.hc.client5.http.impl.InMemoryDnsResolver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.util.unit.DataSize;

import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

/**
 * Egress lanes on the fetch server (webshare cycle (in cycle 20), handoff 1-15 #5): the request names the lane, the server sends through
 * that lane's proxy (JDK and underscore paths), echoes the lane, treats a missing lane as direct and a lane it does
 * not have (or its mode excludes) as ERROR. Two {@link TestHttpProxy} instances stand in for Webshare; a third one
 * refuses like Webshare (407 without a challenge) and proves the credentials go out on the first request.
 */
@ExtendWith(OutputCaptureExtension.class)
class FetchLaneTests {

	static final String TOKEN = "test-token";

	static HttpServer library;

	static TestHttpProxy proxy1;

	static TestHttpProxy proxy2;

	/** Requires {@code user:pw} and refuses like Webshare: 407 without {@code Proxy-Authenticate}. */
	static TestHttpProxy authProxy;

	static HttpsServer tlsLibrary;

	static SSLContext clientTls;

	@BeforeAll
	static void start() throws Exception {
		library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
		library.createContext("/page", exchange -> {
			byte[] body = "<p>ok</p>".getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "text/html; charset=UTF-8");
			exchange.sendResponseHeaders(200, body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		library.start();
		proxy1 = new TestHttpProxy(library.getAddress(), null, null);
		proxy2 = new TestHttpProxy(library.getAddress(), null, null);

		KeyStore keys = KeyStore.getInstance("PKCS12");
		try (InputStream in = FetchLaneTests.class.getResourceAsStream("/tls/test/underscore-host-site.p12")) {
			keys.load(in, "changeit".toCharArray());
		}
		KeyManagerFactory keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
		keyManagers.init(keys, "changeit".toCharArray());
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
		// CONNECT goes to the TLS library whatever host it names (the certificate is for *.example.test)
		authProxy = new TestHttpProxy(tlsLibrary.getAddress(), "user", "pw").webshareLike();
	}

	@AfterAll
	static void stop() throws IOException {
		proxy1.close();
		proxy2.close();
		authProxy.close();
		library.stop(0);
		tlsLibrary.stop(0);
	}

	static FetchProperties properties(FetchProperties.Egress egress) {
		FetchProperties base = UrlGuardTests.properties(true);
		return new FetchProperties(base.tokenHeader(), base.token(), base.userAgent(), base.connectTimeout(),
				base.readTimeout(), base.totalTimeout(), DataSize.ofBytes(1024), 4, true, egress);
	}

	static String proxies() {
		return proxy1.hostPort() + "," + proxy2.hostPort();
	}

	static String url(String path) {
		return "http://127.0.0.1:" + library.getAddress().getPort() + path;
	}

	static MvcTestResult post(MockMvcTester mvc, String url, String lane) {
		return mvc.post()
			.uri("/v1/fetches")
			.contentType(MediaType.APPLICATION_JSON)
			.header("X-Fetch-Token", TOKEN)
			.content("{\"url\":\"" + url + "\",\"requestId\":\"r-1\"" + (lane == null ? "" : ",\"lane\":\"" + lane + "\"")
					+ "}")
			.exchange();
	}

	@Nested
	@SpringBootTest(properties = { "fetch.token=" + TOKEN, "fetch.allow-loopback=true" })
	@AutoConfigureMockMvc
	class EveryLane {

		@DynamicPropertySource
		static void lanes(DynamicPropertyRegistry registry) {
			registry.add("fetch.egress.proxies", FetchLaneTests::proxies);
		}

		@Autowired
		MockMvcTester mvc;

		@BeforeEach
		void clear() {
			proxy1.served.clear();
			proxy2.served.clear();
		}

		@Test
		void theNamedLanesProxyServesTheRequestAndTheAnswerEchoesTheLane(CapturedOutput output) {
			assertThat(post(mvc, url("/page"), "proxy-02")).hasStatusOk()
				.bodyJson()
				.extractingPath("$.outcome")
				.isEqualTo("RESPONDED");
			assertThat(post(mvc, url("/page"), "proxy-02")).bodyJson().extractingPath("$.lane").isEqualTo("proxy-02");
			assertThat(post(mvc, url("/page"), "proxy-01")).bodyJson().extractingPath("$.lane").isEqualTo("proxy-01");

			assertThat(proxy2.served).hasSize(2).allMatch(line -> line.startsWith("GET http://127.0.0.1:"));
			assertThat(proxy1.served).hasSize(1);
			assertThat(output.getOut()).contains("lane=proxy-02 vendor=webshare").doesNotContain(proxy2.hostPort());
		}

		@Test
		void noLaneMeansDirectAndAnUnknownLaneIsAnError() {
			assertThat(post(mvc, url("/page"), null)).bodyJson().extractingPath("$.lane").isEqualTo("direct");
			assertThat(post(mvc, url("/page"), "direct")).bodyJson().extractingPath("$.status").isEqualTo(200);
			assertThat(proxy1.served).isEmpty();
			assertThat(proxy2.served).isEmpty();

			assertThat(post(mvc, url("/page"), "proxy-09")).hasStatusOk()
				.bodyJson()
				.extractingPath("$.outcome")
				.isEqualTo("ERROR");
		}

	}

	@Nested
	@SpringBootTest(properties = { "fetch.token=" + TOKEN, "fetch.allow-loopback=true", "fetch.egress.mode=proxy-only" })
	@AutoConfigureMockMvc
	class ProxiesOnly {

		@DynamicPropertySource
		static void lanes(DynamicPropertyRegistry registry) {
			registry.add("fetch.egress.proxies", FetchLaneTests::proxies);
		}

		@Autowired
		MockMvcTester mvc;

		@Test
		void theDirectLaneIsRefusedWhileProxyLanesWork() {
			assertThat(post(mvc, url("/page"), "direct")).bodyJson().extractingPath("$.outcome").isEqualTo("ERROR");
			assertThat(post(mvc, url("/page"), null)).bodyJson().extractingPath("$.outcome").isEqualTo("ERROR");
			assertThat(post(mvc, url("/page"), "proxy-01")).bodyJson().extractingPath("$.outcome").isEqualTo("RESPONDED");
		}

	}

	/**
	 * A credentialed proxy lane (the local PC, user decision 2026-10-17) against a proxy that refuses like Webshare:
	 * the first CONNECT / first request already carries Basic credentials, so https, http and underscore hosts all
	 * succeed on the first try; without or with wrong credentials the proxy's 407 is the answer (RESPONDED 407, a
	 * block signal in the main service).
	 */
	@Nested
	class WebshareLikeProxyWithCredentials {

		InMemoryDnsResolver dns = new InMemoryDnsResolver();

		@BeforeEach
		void clear() throws IOException {
			authProxy.served.clear();
			authProxy.refused.set(0);
			dns.add("127.0.0.1", InetAddress.getByName("127.0.0.1"));
		}

		LibraryClient client(String username, String password) {
			return new LibraryClient(properties(new FetchProperties.Egress(FetchProperties.Egress.Mode.PROXY_ONLY,
					authProxy.hostPort(), "webshare", username, password)), dns, clientTls);
		}

		@Test
		void httpsAndUnderscoreHostsSucceedOnTheFirstRequest(CapturedOutput output) throws IOException {
			URI https = URI.create("https://site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
			URI underscore = URI.create("https://hs_site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
			try (LibraryClient client = client("user", "pw")) {
				EgressLane lane = client.lane("proxy-01");
				FetchResult tunneled = client.get(https, "r-s", lane);
				assertThat(tunneled.outcome()).isEqualTo(FetchResult.Outcome.RESPONDED);
				assertThat(tunneled.status()).as("the first CONNECT carries Proxy-Authorization").isEqualTo(200);
				assertThat(tunneled.lane()).isEqualTo("proxy-01");
				assertThat(client.get(underscore, "r-u", lane).status()).isEqualTo(200);
			}
			assertThat(authProxy.refused.get()).isZero();
			assertThat(authProxy.served).hasSize(2).allMatch(line -> line.startsWith("CONNECT "));
			assertThat(output.getOut()).contains("lane=proxy-01 vendor=webshare")
				.doesNotContain(authProxy.hostPort())
				.doesNotContain("dXNlcjpwdw==");
		}

		@Test
		void plainHttpCarriesTheCredentialsOnTheFirstRequestToo() throws IOException {
			// a plain http request is forwarded to the plain library, so a proxy of the same kind in front of it
			try (TestHttpProxy plainProxy = new TestHttpProxy(library.getAddress(), "user", "pw").webshareLike();
					LibraryClient client = new LibraryClient(
							properties(new FetchProperties.Egress(FetchProperties.Egress.Mode.PROXY_ONLY,
									plainProxy.hostPort(), "webshare", "user", "pw")),
							dns, clientTls)) {
				FetchResult result = client.get(URI.create(url("/page")), "r-h", client.lane("proxy-01"));
				assertThat(result.status()).isEqualTo(200);
				assertThat(plainProxy.refused.get()).isZero();
				assertThat(plainProxy.served).hasSize(1).first().asString().startsWith("GET http://127.0.0.1:");
			}
		}

		@Test
		void withoutOrWithWrongCredentialsTheProxys407IsTheAnswer() throws IOException {
			URI https = URI.create("https://site.example.test:" + tlsLibrary.getAddress().getPort() + "/");
			try (LibraryClient none = client("", ""); LibraryClient wrong = client("user", "wrong")) {
				FetchResult refused = none.get(https, "r-n", none.lane("proxy-01"));
				assertThat(refused.outcome()).isEqualTo(FetchResult.Outcome.RESPONDED);
				assertThat(refused.status()).isEqualTo(407);
				assertThat(refused.lane()).isEqualTo("proxy-01");
				assertThat(wrong.get(https, "r-w", wrong.lane("proxy-01")).status()).isEqualTo(407);
			}
			assertThat(authProxy.refused.get()).isEqualTo(2);
			assertThat(authProxy.served).isEmpty();
		}

	}

	/** The Apache path for underscore host names goes through the lane's proxy too (no local DNS needed). */
	@Nested
	class UnderscoreHostThroughTheProxy {

		LibraryClient client;

		@BeforeEach
		void client() throws IOException {
			proxy1.served.clear();
			FetchProperties base = UrlGuardTests.properties(true);
			FetchProperties properties = new FetchProperties(base.tokenHeader(), base.token(), base.userAgent(),
					base.connectTimeout(), base.readTimeout(), base.totalTimeout(), DataSize.ofBytes(1024), 4, true,
					new FetchProperties.Egress(FetchProperties.Egress.Mode.ALL, proxies(), "webshare", "", ""));
			// the Apache client resolves the proxy's host through its resolver, the library's name is the proxy's job
			InMemoryDnsResolver dns = new InMemoryDnsResolver();
			dns.add("127.0.0.1", InetAddress.getByName("127.0.0.1"));
			client = new LibraryClient(properties, dns);
		}

		@AfterEach
		void close() throws IOException {
			client.close();
		}

		@Test
		void underscoreHostIsSentToTheProxy() {
			URI uri = URI.create("http://hs_lib.example.test:" + library.getAddress().getPort() + "/page");
			FetchResult result = client.get(uri, "r-u", client.lane("proxy-01"));

			assertThat(result.outcome()).isEqualTo(FetchResult.Outcome.RESPONDED);
			assertThat(result.status()).isEqualTo(200);
			assertThat(result.lane()).isEqualTo("proxy-01");
			assertThat(proxy1.served).hasSize(1).first().asString().startsWith("GET http://hs_lib.example.test:");
		}

		@Test
		void proxyOnlyModeHasNoDirectLane() {
			FetchProperties base = UrlGuardTests.properties(true);
			FetchProperties properties = new FetchProperties(base.tokenHeader(), base.token(), base.userAgent(),
					base.connectTimeout(), base.readTimeout(), base.totalTimeout(), DataSize.ofBytes(1024), 4, true,
					new FetchProperties.Egress(FetchProperties.Egress.Mode.PROXY_ONLY, proxies(), "webshare", "", ""));
			try (LibraryClient proxiesOnly = new LibraryClient(properties, new InMemoryDnsResolver())) {
				assertThat(proxiesOnly.lane("direct")).isNull();
				assertThat(proxiesOnly.lane("proxy-02")).isNotNull();
			}
			catch (IOException ex) {
				throw new RuntimeException(ex);
			}
		}

	}

}
