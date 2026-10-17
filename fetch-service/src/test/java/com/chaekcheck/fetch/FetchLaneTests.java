package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.Executors;

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

/**
 * Egress lanes on the fetch server (webshare cycle (in cycle 20), handoff 1-15 #5): the request names the lane, the server sends through
 * that lane's proxy (JDK and underscore paths), echoes the lane, treats a missing lane as direct and a lane it does
 * not have (or its mode excludes) as ERROR. Two {@link TestHttpProxy} instances stand in for Webshare.
 */
@ExtendWith(OutputCaptureExtension.class)
class FetchLaneTests {

	static final String TOKEN = "test-token";

	static HttpServer library;

	static TestHttpProxy proxy1;

	static TestHttpProxy proxy2;

	@BeforeAll
	static void start() throws IOException {
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
	}

	@AfterAll
	static void stop() throws IOException {
		proxy1.close();
		proxy2.close();
		library.stop(0);
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
