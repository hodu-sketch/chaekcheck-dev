package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.sun.net.httpserver.HttpServer;

class FetchControllerTests {

	static final String TOKEN = "test-token";

	/** Default settings: loopback is refused like any non-public address. */
	@Nested
	@SpringBootTest(properties = { "fetch.token=" + TOKEN })
	@AutoConfigureMockMvc
	class Guards {

		@Autowired
		MockMvcTester mvc;

		@Test
		void missingOrWrongTokenIs401() {
			assertThat(post(mvc, null, "https://example.com/")).hasStatus(401).bodyJson()
				.extractingPath("$.code").isEqualTo("unauthorized");
			assertThat(post(mvc, "wrong", "https://example.com/")).hasStatus(401);
		}

		@Test
		void privateLoopbackAndLinkLocalAddressesAre400() {
			for (String url : new String[] { "http://127.0.0.1/", "http://localhost:8080/", "http://10.0.0.1/",
					"http://192.168.0.1/", "http://169.254.169.254/latest/meta-data/", "http://[::1]/",
					"http://100.64.0.1/" }) {
				assertThat(post(mvc, TOKEN, url)).as(url).hasStatus(400).bodyJson()
					.extractingPath("$.code").isEqualTo("invalid_url");
			}
		}

		@Test
		void nonHttpSchemesAre400() {
			assertThat(post(mvc, TOKEN, "file:///etc/passwd")).hasStatus(400);
			assertThat(post(mvc, TOKEN, "ftp://example.com/")).hasStatus(400);
			assertThat(post(mvc, TOKEN, "not a url")).hasStatus(400);
		}

	}

	/** A fake library on loopback: loopback is allowed only in this test configuration. */
	@Nested
	@SpringBootTest(properties = { "fetch.token=" + TOKEN, "fetch.allow-loopback=true", "fetch.read-timeout=500ms" })
	@AutoConfigureMockMvc
	class AgainstAFakeLibrary {

		@Autowired
		MockMvcTester mvc;

		HttpServer library;

		@BeforeEach
		void start() throws IOException {
			library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			library.createContext("/euc-kr", exchange -> {
				byte[] body = "<p>채식주의자</p>".getBytes(Charset.forName("EUC-KR"));
				exchange.getResponseHeaders().add("Content-Type", "text/html; charset=EUC-KR");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			library.createContext("/moved", exchange -> {
				exchange.getResponseHeaders().add("Location", "/elsewhere");
				exchange.sendResponseHeaders(301, -1);
				exchange.close();
			});
			library.createContext("/forbidden", exchange -> {
				exchange.sendResponseHeaders(403, -1);
				exchange.close();
			});
			library.createContext("/slow", exchange -> {
				try {
					Thread.sleep(1500);
					exchange.sendResponseHeaders(200, -1);
				}
				catch (InterruptedException | IOException ignored) {
					// the client gave up
				}
				exchange.close();
			});
			library.createContext("/ua", exchange -> {
				byte[] body = exchange.getRequestHeaders().getFirst("User-Agent").getBytes(StandardCharsets.UTF_8);
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			library.start();
		}

		@AfterEach
		void stop() {
			library.stop(0);
		}

		@Test
		void rawBytesComeBackAsBase64() {
			MvcTestResult result = post(mvc, TOKEN, url("/euc-kr"));

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("RESPONDED");
			assertThat(result).bodyJson().extractingPath("$.contentType").isEqualTo("text/html; charset=EUC-KR");
			assertThat(result).bodyJson()
				.extractingPath("$.body")
				.isEqualTo(Base64.getEncoder().encodeToString("<p>채식주의자</p>".getBytes(Charset.forName("EUC-KR"))));
		}

		@Test
		void redirectIsNotFollowedAndLocationIsReturned() {
			MvcTestResult result = post(mvc, TOKEN, url("/moved"));

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.status").isEqualTo(301);
			assertThat(result).bodyJson().extractingPath("$.location").isEqualTo("/elsewhere");
		}

		@Test
		void libraryErrorStatusIsStill200WithTheStatusInside() {
			MvcTestResult result = post(mvc, TOKEN, url("/forbidden"));

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.status").isEqualTo(403);
		}

		@Test
		void libraryTimeoutIsAnOutcome() {
			assertThat(post(mvc, TOKEN, url("/slow"))).hasStatus(200).bodyJson()
				.extractingPath("$.outcome").isEqualTo("TIMEOUT");
		}

		@Test
		void sendsTheConfiguredUserAgent() {
			assertThat(post(mvc, TOKEN, url("/ua"))).bodyJson()
				.extractingPath("$.body")
				.isEqualTo(Base64.getEncoder().encodeToString("chaekcheck/0.0.1".getBytes(StandardCharsets.UTF_8)));
		}

		private String url(String path) {
			return "http://127.0.0.1:" + library.getAddress().getPort() + path;
		}

	}

	static MvcTestResult post(MockMvcTester mvc, String token, String url) {
		var request = mvc.post()
			.uri("/v1/fetches")
			.contentType(MediaType.APPLICATION_JSON)
			.content("{\"url\":\"" + url + "\",\"requestId\":\"r-1\"}");
		if (token != null) {
			request = request.header("X-Fetch-Token", token);
		}
		return request.exchange();
	}

}
