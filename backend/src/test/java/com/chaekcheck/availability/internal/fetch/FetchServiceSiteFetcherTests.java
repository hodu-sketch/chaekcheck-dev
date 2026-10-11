package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

/**
 * Failover rules (ADR-0015, design-delta flow 4) against fake fetch servers on loopback.
 */
class FetchServiceSiteFetcherTests {

	static final URI LIBRARY = URI.create("https://lib.test/search?q=x");

	static final Duration READ_TIMEOUT = Duration.ofMillis(500);

	final JsonMapper json = JsonMapper.builder().build();

	final List<FakeServer> servers = new ArrayList<>();

	@AfterEach
	void stop() {
		servers.forEach(FakeServer::stop);
	}

	@Test
	void refusedConnectionGoesToTheNextServer() {
		FakeServer second = server(exchange -> responded(200, "<p>ok</p>".getBytes(StandardCharsets.UTF_8)));
		FetchServiceSiteFetcher fetcher = fetcher(closedPortUrl(), second.url());

		SiteResponse response = fetcher.fetch(LIBRARY, deadline());

		assertThat(response.status()).isEqualTo(200);
		assertThat(response.via()).isEqualTo("fetch-2");
		assertThat(second.hits).hasSize(1);
	}

	@Test
	void unavailableServerRestsAndIsSkippedNextTime() {
		FakeServer first = server(exchange -> new Reply(503, "{}"));
		FakeServer second = server(exchange -> responded(200, new byte[0]));
		FetchServiceSiteFetcher fetcher = fetcher(first.url(), second.url());

		fetcher.fetch(LIBRARY, deadline());
		fetcher.fetch(LIBRARY, deadline());

		assertThat(first.hits).hasSize(1);
		assertThat(second.hits).hasSize(2);
	}

	@Test
	void slowFetchServerIsNotFailedOver() {
		FakeServer first = server(exchange -> {
			sleep(READ_TIMEOUT.plusMillis(500));
			return responded(200, new byte[0]);
		});
		FakeServer second = server(exchange -> responded(200, new byte[0]));
		FetchServiceSiteFetcher fetcher = fetcher(first.url(), second.url());

		assertThatThrownBy(() -> fetcher.fetch(LIBRARY, deadline()))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));
		assertThat(second.hits).isEmpty();
	}

	@Test
	void libraryFailuresAreNotFailedOver() {
		FakeServer first = server(exchange -> responded(403, new byte[0]));
		FakeServer second = server(exchange -> responded(200, new byte[0]));
		FetchServiceSiteFetcher fetcher = fetcher(first.url(), second.url());

		assertThat(fetcher.fetch(LIBRARY, deadline()).status()).isEqualTo(403);
		assertThat(second.hits).isEmpty();
	}

	@Test
	void libraryTimeoutOnTheFetchServerIsATimeout() {
		FakeServer first = server(exchange -> new Reply(200, """
				{"outcome":"TIMEOUT","status":null,"contentType":null,"location":null,"body":null,
				 "startedAt":"2026-09-28T00:00:00Z","endedAt":"2026-09-28T00:00:05Z"}"""));
		FakeServer second = server(exchange -> responded(200, new byte[0]));
		FetchServiceSiteFetcher fetcher = fetcher(first.url(), second.url());

		assertThatThrownBy(() -> fetcher.fetch(LIBRARY, deadline()))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.TIMEOUT));
		assertThat(second.hits).isEmpty();
	}

	@Test
	void allServersRestingFailsWithoutCallingTheLibraryDirectly() {
		FakeServer first = server(exchange -> new Reply(503, "{}"));
		FetchServiceSiteFetcher fetcher = fetcher(first.url(), closedPortUrl());

		assertThatThrownBy(() -> fetcher.fetch(LIBRARY, deadline()))
			.isInstanceOfSatisfying(UpstreamException.class,
					e -> assertThat(e.kind()).isEqualTo(UpstreamException.Kind.ERROR));
		assertThatThrownBy(() -> fetcher.fetch(LIBRARY, deadline())).isInstanceOf(UpstreamException.class);
		assertThat(first.hits).hasSize(1);
	}

	@Test
	void sendsTokenAndUrlAndKeepsRawBytes() { // EUC-KR body stays byte for byte
		byte[] eucKr = "<p>채식주의자</p>".getBytes(Charset.forName("EUC-KR"));
		FakeServer first = server(exchange -> new Reply(200, """
				{"outcome":"RESPONDED","status":200,"contentType":"text/html; charset=EUC-KR","location":null,
				 "body":"%s","startedAt":"2026-09-28T00:00:00Z","endedAt":"2026-09-28T00:00:01Z"}"""
			.formatted(Base64.getEncoder().encodeToString(eucKr))));
		FetchServiceSiteFetcher fetcher = fetcher(first.url());

		SiteResponse response = fetcher.fetch(LIBRARY, deadline());

		assertThat(response.body()).isEqualTo(eucKr);
		assertThat(response.declaredCharset()).isEqualTo(Charset.forName("EUC-KR"));
		assertThat(first.hits.getFirst().token()).isEqualTo("secret");
		assertThat(first.hits.getFirst().body()).contains("\"url\":\"https://lib.test/search?q=x\"");
	}

	@Test
	void redirectLocationIsReturnedNotFollowed() {
		FakeServer first = server(exchange -> new Reply(200, """
				{"outcome":"RESPONDED","status":301,"contentType":null,"location":"https://lib.test/new",
				 "body":"","startedAt":"2026-09-28T00:00:00Z","endedAt":"2026-09-28T00:00:01Z"}"""));

		SiteResponse response = fetcher(first.url()).fetch(LIBRARY, deadline());

		assertThat(response.status()).isEqualTo(301);
		assertThat(response.location()).isEqualTo("https://lib.test/new");
		// "" decodes to no bytes, as Base64.getDecoder() gave before the body went straight to bytes (cycle 08)
		assertThat(response.body()).isEmpty();
	}

	private FetchServiceSiteFetcher fetcher(URI... urls) {
		List<FetchServiceSiteFetcher.Server> list = new ArrayList<>();
		for (int i = 0; i < urls.length; i++) {
			list.add(new FetchServiceSiteFetcher.Server("fetch-" + (i + 1), urls[i]));
		}
		return new FetchServiceSiteFetcher(list, "X-Fetch-Token", "secret", Duration.ofMillis(300), READ_TIMEOUT,
				Duration.ofMinutes(1), json, Clock.systemUTC());
	}

	private static Instant deadline() {
		return Instant.now().plusSeconds(5);
	}

	private Reply responded(int status, byte[] body) {
		return new Reply(200, """
				{"outcome":"RESPONDED","status":%d,"contentType":"text/html","location":null,"body":"%s",
				 "startedAt":"2026-09-28T00:00:00Z","endedAt":"2026-09-28T00:00:01Z"}"""
			.formatted(status, Base64.getEncoder().encodeToString(body)));
	}

	private FakeServer server(Function<HttpExchange, Reply> behavior) {
		FakeServer server = new FakeServer(behavior);
		servers.add(server);
		return server;
	}

	private static URI closedPortUrl() {
		try (ServerSocket socket = new ServerSocket(0)) {
			return URI.create("http://127.0.0.1:" + socket.getLocalPort());
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void sleep(Duration duration) {
		try {
			Thread.sleep(duration);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

	record Reply(int status, String json) {
	}

	record Hit(String token, String body) {
	}

	static final class FakeServer {

		final List<Hit> hits = new CopyOnWriteArrayList<>();

		private final HttpServer server;

		FakeServer(Function<HttpExchange, Reply> behavior) {
			try {
				server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			}
			catch (IOException ex) {
				throw new IllegalStateException(ex);
			}
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.createContext("/v1/fetches", exchange -> {
				hits.add(new Hit(exchange.getRequestHeaders().getFirst("X-Fetch-Token"),
						new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
				Reply reply = behavior.apply(exchange);
				byte[] bytes = reply.json().getBytes(StandardCharsets.UTF_8);
				exchange.getResponseHeaders().add("Content-Type", "application/json");
				try {
					exchange.sendResponseHeaders(reply.status(), bytes.length);
					exchange.getResponseBody().write(bytes);
				}
				catch (IOException ignored) {
					// the client gave up (read timeout test)
				}
				exchange.close();
			});
			server.start();
		}

		URI url() {
			return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
		}

		void stop() {
			server.stop(0);
		}

	}

}
