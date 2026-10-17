package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
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
			// cycle 20: gzip is asked for and inflated; the EUC-KR bytes come back byte for byte
			library.createContext("/gzip-euc-kr", exchange -> {
				acceptEncodings.add(String.valueOf(exchange.getRequestHeaders().getFirst("Accept-Encoding")));
				byte[] body = LibraryClientUnderscoreHostTests.gzip("<p>채식주의자</p>".getBytes(Charset.forName("EUC-KR")));
				exchange.getResponseHeaders().add("Content-Type", "text/html; charset=EUC-KR");
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
			library.start();
		}

		final java.util.List<String> acceptEncodings = new java.util.concurrent.CopyOnWriteArrayList<>();

		@Test
		void gzipAnswerIsInflatedToTheSitesBytes() { // cycle 20 (AC-34)
			MvcTestResult result = post(mvc, TOKEN, url("/gzip-euc-kr"));

			assertThat(acceptEncodings).containsExactly("gzip");
			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("RESPONDED");
			assertThat(result).bodyJson()
				.extractingPath("$.body")
				.isEqualTo(Base64.getEncoder().encodeToString("<p>채식주의자</p>".getBytes(Charset.forName("EUC-KR"))));
		}

		@Test
		void encodingNotAskedForIsAnError() { // cycle 20 [안]: only gzip is inflated
			assertThat(post(mvc, TOKEN, url("/brotli"))).hasStatus(200).bodyJson()
				.extractingPath("$.outcome").isEqualTo("ERROR");
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

	/** Small limits so a fake library can pass them: 1KB body, 1s for the whole request. */
	@Nested
	@SpringBootTest(properties = { "fetch.token=" + TOKEN, "fetch.allow-loopback=true", "fetch.read-timeout=500ms",
			"fetch.total-timeout=1s", "fetch.max-body-size=1KB" })
	@AutoConfigureMockMvc
	class Limits {

		@Autowired
		MockMvcTester mvc;

		HttpServer library;

		@BeforeEach
		void start() throws IOException {
			library = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			library.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			library.createContext("/fits", exchange -> {
				byte[] body = new byte[1024];
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			// Content-Length says too big: refused before the body is read
			library.createContext("/declared-big", exchange -> {
				byte[] body = new byte[1025];
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
			// cycle 20: about 90 bytes on the wire that inflate to 64KB, over the 1KB limit
			library.createContext("/gzip-bomb", exchange -> {
				byte[] body = LibraryClientUnderscoreHostTests.gzip(new byte[64 * 1024]);
				exchange.getResponseHeaders().add("Content-Encoding", "gzip");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
				exchange.close();
			});
			// gzip that inflates to exactly the limit: returned
			library.createContext("/gzip-fits", exchange -> {
				byte[] body = LibraryClientUnderscoreHostTests.gzip(new byte[1024]);
				exchange.getResponseHeaders().add("Content-Encoding", "gzip");
				exchange.sendResponseHeaders(200, body.length);
				exchange.getResponseBody().write(body);
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
			library.start();
		}

		@AfterEach
		void stop() {
			library.stop(0);
		}

		@Test
		void bodyAtTheLimitIsReturned() {
			assertThat(post(mvc, TOKEN, url("/fits"))).hasStatus(200).bodyJson()
				.extractingPath("$.outcome").isEqualTo("RESPONDED");
		}

		@Test
		void bodyDeclaredOverTheLimitFails() { // cycle 20: BODY_TOO_LARGE, with the declared length
			MvcTestResult result = post(mvc, TOKEN, url("/declared-big"));

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("BODY_TOO_LARGE");
			assertThat(result).bodyJson().extractingPath("$.receivedBytes").isEqualTo(1025);
		}

		@Test
		void chunkedBodyOverTheLimitFails() {
			MvcTestResult result = post(mvc, TOKEN, url("/chunked-big"));

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("BODY_TOO_LARGE");
			assertThat(result).bodyJson().extractingPath("$.body").isNull();
		}

		@Test
		void gzipBombIsBodyTooLargeAndGzipAtTheLimitIsReturned() { // cycle 20 (AC-34)
			MvcTestResult bomb = post(mvc, TOKEN, url("/gzip-bomb"));
			assertThat(bomb).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("BODY_TOO_LARGE");
			assertThat(bomb).bodyJson().extractingPath("$.body").isNull();

			MvcTestResult fits = post(mvc, TOKEN, url("/gzip-fits"));
			assertThat(fits).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("RESPONDED");
			assertThat(fits).bodyJson().extractingPath("$.body")
				.isEqualTo(Base64.getEncoder().encodeToString(new byte[1024]));
		}

		@Test
		void slowBodyHitsTheTotalTimeLimit() {
			long start = System.nanoTime();
			MvcTestResult result = post(mvc, TOKEN, url("/trickle"));
			long tookMillis = (System.nanoTime() - start) / 1_000_000;

			assertThat(result).hasStatus(200).bodyJson().extractingPath("$.outcome").isEqualTo("TIMEOUT");
			// the body would take 3s; the 1s total limit stops it
			assertThat(tookMillis).isBetween(900L, 2500L);
		}

		private String url(String path) {
			return "http://127.0.0.1:" + library.getAddress().getPort() + path;
		}

	}

	/** @return false once the client has gone */
	static boolean writeQuietly(OutputStream out, byte[] bytes) {
		try {
			out.write(bytes);
			out.flush();
			return true;
		}
		catch (IOException ex) {
			return false;
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
