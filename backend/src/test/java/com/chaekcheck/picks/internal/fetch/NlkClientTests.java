package com.chaekcheck.picks.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.time.Duration;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.util.unit.DataSize;

import com.chaekcheck.picks.internal.PicksProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * The list request and covers against a local JDK {@link HttpServer} with the real {@link HttpNlkTransport} (plan
 * cycle 14 step 3; C4, C5). Never the real API (C3). The key is a test value.
 */
class NlkClientTests {

	static final String KEY = "test-secret-key-123";

	HttpServer server;

	final List<URI> requests = new CopyOnWriteArrayList<>();

	volatile Answer answer;

	HttpNlkTransport transport;

	ListAppender<ILoggingEvent> logs;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		server.setExecutor(Executors.newCachedThreadPool());
		server.start();
		transport = new HttpNlkTransport(Duration.ofSeconds(5), Duration.ofMillis(800), "book search service");
		logs = new ListAppender<>();
		logs.start();
		for (Class<?> type : List.of(NlkClient.class, HttpNlkTransport.class)) {
			((Logger) LoggerFactory.getLogger(type)).addAppender(logs);
		}
		answer = new Answer(200, "text/xml;charset=UTF-8", NlkFixtures.bytes(NlkFixtures.saseo2y()), 0, null);
	}

	@AfterEach
	void stop() {
		server.stop(0);
		transport.close();
		for (Class<?> type : List.of(NlkClient.class, HttpNlkTransport.class)) {
			((Logger) LoggerFactory.getLogger(type)).detachAppender(logs);
		}
	}

	@Test
	void oneRequestWithTheTwoYearWindow() throws Exception {
		byte[] body = client(KEY).fetchList(LocalDate.of(2026, 10, 12));

		assertThat(body).hasSize(NlkFixtures.bytes(NlkFixtures.saseo2y()).length);
		assertThat(requests).hasSize(1);
		URI uri = requests.getFirst();
		assertThat(uri.getPath()).isEqualTo("/NL/search/openApi/saseoApi.do");
		assertThat(uri.getRawQuery()).isEqualTo("key=" + KEY
				+ "&startRowNumApi=1&endRowNumApi=1000&start_date=20241012&end_date=20261012");
		assertThat(logText()).contains("GET /NL/search/openApi/saseoApi.do?key=***&startRowNumApi=1")
			.contains("-> 200");
	}

	@Test
	void redirectIsRejectedAndNotFollowed() {
		answer = new Answer(302, "text/html", new byte[0], 0, "/somewhere");

		assertFailure(KEY, FailureReason.REQUEST_REJECTED, "HTTP 302");
		assertThat(requests).hasSize(1);
	}

	@Test
	void slowAnswerIsANetworkError() {
		answer = new Answer(200, "text/xml", new byte[0], 2_000, null);

		assertFailure(KEY, FailureReason.NETWORK_ERROR, "time-out");
	}

	@Test
	void bodyOverTheLimitFails() {
		answer = new Answer(200, "text/xml", new byte[2048], 0, null);

		assertThatThrownBy(() -> client(KEY, DataSize.ofKilobytes(1)).fetchList(LocalDate.of(2026, 10, 12)))
			.isInstanceOf(FetchFailure.class)
			.extracting(ex -> ((FetchFailure) ex).reason())
			.isEqualTo(FailureReason.FORMAT_CHANGED);
	}

	@Test
	void noKeyMeansNoRequest() {
		assertFailure("", FailureReason.KEY_MISSING, "no API key");
		assertFailure(null, FailureReason.KEY_MISSING, "no API key");
		assertThat(requests).isEmpty();
	}

	/** C5: the key is in no log line, exception message, failure_detail or settings text. */
	@Test
	void theKeyIsNeverShown() throws Exception {
		client(KEY).fetchList(LocalDate.of(2026, 10, 12));
		answer = new Answer(401, "text/plain", ("bad key " + KEY).getBytes(), 0, null);
		FetchFailure rejected = failure(KEY);
		answer = new Answer(200, "text/xml", new byte[0], 2_000, null);
		FetchFailure timeOut = failure(KEY);
		server.stop(0);
		FetchFailure refused = failure(KEY);

		assertThat(List.of(rejected, timeOut, refused)).extracting(FetchFailure::reason)
			.containsExactly(FailureReason.REQUEST_REJECTED, FailureReason.NETWORK_ERROR, FailureReason.NETWORK_ERROR);
		assertThat(requests).hasSize(3);
		assertThat(logText()).isNotEmpty().doesNotContain(KEY);
		for (FetchFailure failure : List.of(rejected, timeOut, refused)) {
			assertThat(String.valueOf(failure.getMessage())).doesNotContain(KEY).doesNotContain("saseoApi");
			assertThat(failure.getCause()).isNull();
		}
		assertThat(settings(KEY, DataSize.ofMegabytes(10)).toString()).doesNotContain(KEY).contains("apiKey=***");
	}

	@Test
	void coversNeedAnImageAndAnAllowedAddress() throws Exception {
		NlkClient client = client(KEY);
		answer = new Answer(200, "image/jpeg", new byte[] { 1, 2, 3 }, 0, null);

		// the local server is not www.nl.go.kr: refused before any request
		assertThat(client.fetchCover("https://localhost/afile/a")).isEmpty();
		assertThat(client.fetchCover("http://www.nl.go.kr/afile/a")).isEmpty();
		assertThat(requests).isEmpty();
	}

	private NlkClient client(String key) {
		return client(key, DataSize.ofMegabytes(10));
	}

	private NlkClient client(String key, DataSize maxBody) {
		return new NlkClient(transport, new RequestPacer(Duration.ofSeconds(1), new NlkFixtures.FakeTicker()),
				settings(key, maxBody));
	}

	private PicksProperties.Fetch settings(String key, DataSize maxBody) {
		return new PicksProperties.Fetch(true, key, "0 0 5 * * *", ZoneId.of("Asia/Seoul"),
				URI.create("http://localhost:" + server.getAddress().getPort()), Period.ofYears(2), 1000,
				Duration.ofSeconds(5), Duration.ofMillis(800), maxBody, DataSize.ofMegabytes(5), Duration.ofSeconds(1),
				"book search service", new PicksProperties.Cover(200, 0.8f, 16_000_000));
	}

	private void assertFailure(String key, FailureReason reason, String detail) {
		FetchFailure failure = failure(key);
		assertThat(failure.reason()).isEqualTo(reason);
		assertThat(failure.getMessage()).isEqualTo(detail);
	}

	private FetchFailure failure(String key) {
		try {
			client(key).fetchList(LocalDate.of(2026, 10, 12));
		}
		catch (FetchFailure failure) {
			return failure;
		}
		catch (InterruptedException ex) {
			throw new AssertionError(ex);
		}
		throw new AssertionError("no failure");
	}

	private String logText() {
		StringBuilder text = new StringBuilder();
		logs.list.forEach(event -> text.append(event.getFormattedMessage()).append('\n'));
		return text.toString();
	}

	private void handle(HttpExchange exchange) throws IOException {
		requests.add(exchange.getRequestURI());
		Answer a = answer;
		try {
			if (a.delayMillis() > 0) {
				Thread.sleep(a.delayMillis());
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		if (a.location() != null) {
			exchange.getResponseHeaders().add("Location", a.location());
		}
		exchange.getResponseHeaders().add("Content-Type", a.contentType());
		exchange.sendResponseHeaders(a.status(), a.body().length == 0 ? -1 : a.body().length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(a.body());
		}
		catch (IOException ex) {
			// the client gave up
		}
	}

	record Answer(int status, String contentType, byte[] body, long delayMillis, String location) {
	}

}
