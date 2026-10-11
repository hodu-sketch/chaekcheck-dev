package com.chaekcheck.popular.internal.fetch;

import static com.chaekcheck.popular.internal.fetch.KpipaFixtures.html;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.popular.internal.PopularCategory;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The client against a local JDK HTTP server with the saved answers (plan cycle 10 C3: never the real site), through
 * the real {@link HttpKpipaTransport}: session cookie, token and headers, failures and the 2-second gaps (AC-12).
 */
class KpipaClientTests {

	static final String TOKEN = "331b283c-56ca-453b-a420-c68a4c7855c9";

	static final String USER_AGENT = "book search service";

	HttpServer server;

	String base;

	final List<Seen> seen = new CopyOnWriteArrayList<>();

	/** Answers by request; replaced per test. */
	volatile Function<Seen, Answer> answers = KpipaClientTests::siteAnswers;

	KpipaFixtures.FakeTicker ticker = new KpipaFixtures.FakeTicker();

	RequestPacer pacer = new RequestPacer(Duration.ofSeconds(2), ticker);

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
		server.createContext("/", this::handle);
		server.start();
		base = "http://127.0.0.1:" + server.getAddress().getPort();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void oneSessionThenFifteenListsAndFourteenCounts() throws Exception {
		MonthRanking month;
		try (KpipaTransport.Connection connection = transport(Duration.ofSeconds(5)).open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			month = client.fetchRanking(YearMonth.of(2026, 9));
			assertThat(client.requestCount()).isEqualTo(30);
		}

		assertThat(month.requestCount()).isEqualTo(30);
		assertThat(month.categories()).hasSize(15).allSatisfy(list -> assertThat(list.books()).hasSize(50));
		assertThat(month.bookCount()).isEqualTo(750);
		assertThat(month.categories().getFirst().salesCopies()).isNull();
		assertThat(month.categories().getFirst().displayOrder()).isZero();
		// every count is the same in this test: the order is by site id
		assertThat(month.categories())
			.filteredOn(list -> list.category() == PopularCategory.ARTS)
			.singleElement()
			.satisfies(list -> {
				assertThat(list.displayOrder()).isEqualTo(1);
				assertThat(list.salesCopies()).isEqualTo(883_478L);
			});

		assertThat(seen).hasSize(30);
		Seen session = seen.getFirst();
		assertThat(session.method()).isEqualTo("GET");
		assertThat(session.path()).isEqualTo("/home/v3/addition/adiStatsCtgSaleRank");
		assertThat(session.headers().get("User-agent")).isEqualTo(USER_AGENT);

		Seen novel = seen.get(2);
		assertThat(novel.method()).isEqualTo("POST");
		assertThat(novel.path()).isEqualTo("/home/v3/addition/adiStatsCtgSaleRank/search");
		assertThat(novel.headers()).containsEntry("Cookie", "JSESSIONID=SESSION1")
			.containsEntry("X-csrf-token", TOKEN)
			.containsEntry("X-requested-with", "XMLHttpRequest")
			.containsEntry("Referer", base + "/home/v3/addition/adiStatsCtgSaleRank")
			.containsEntry("Content-type", "application/x-www-form-urlencoded")
			.containsEntry("User-agent", USER_AGENT);
		assertThat(novel.body()).isEqualTo("rdoSearchType=bookType&txtDate=202609&searchCtg=00000000000000000303"
				+ "&strTypeArr=&searchOrder=&depth=1depth&gubun=N");
		assertThat(seen.get(1).body()).contains("&searchCtg=&");

		Seen sales = seen.get(16);
		assertThat(sales.path()).isEqualTo("/home/v3/addition/adiStatsCtgSaleCnt/search");
		assertThat(sales.headers()).containsEntry("Cookie", "JSESSIONID=SESSION1")
			.containsEntry("X-csrf-token", TOKEN)
			.containsEntry("Referer", base + "/home/v3/addition/adiStatsCtgSaleCnt");
		assertThat(sales.body()).isEqualTo("rdoSearchType=bookType&rdoSearchPeriod=month&txtFromDate=202609"
				+ "&txtToDate=202609&searchCtg=00000000000000000303&depth=1depth");
		assertThat(seen).filteredOn(request -> request.path().endsWith("SaleCnt/search")).hasSize(14);

		// AC-12: 29 gaps between 30 requests, each at least 2 seconds
		assertThat(pacer.gaps()).hasSize(29).allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(Duration.ofSeconds(2)));
	}

	@Test
	void noTokenIsSessionFailed() {
		answers = request -> new Answer(200, "text/html;charset=UTF-8", "<html>no token</html>", Map.of());

		assertFailure(FailureReason.SESSION_FAILED, 1);
	}

	@Test
	void sessionPageNot200IsSessionFailed() {
		answers = request -> new Answer(500, "text/html", "error", Map.of());

		assertFailure(FailureReason.SESSION_FAILED, 1);
	}

	@Test
	void redirectToAccessDeniedIsRequestRejected() {
		answers = request -> request.method().equals("GET") ? siteAnswers(request)
				: new Answer(302, "text/html", "", Map.of("Location", "/home/error/cmmn/accessDenied"));

		FetchFailure failure = assertFailure(FailureReason.REQUEST_REJECTED, 2);
		assertThat(failure.getMessage()).contains("전체").contains("302");
	}

	@Test
	void fewBooksStopsTheFetch() {
		answers = request -> request.body().contains("searchCtg=00000000000000000223")
				? new Answer(200, "text/html", html("rank-search-49-books.html"), Map.of()) : siteAnswers(request);

		FetchFailure failure = assertFailure(FailureReason.TOO_FEW_BOOKS, 4);
		assertThat(failure.getMessage()).isEqualTo("문학·인물: 49 books");
	}

	@Test
	void timeOutIsNetworkError() {
		answers = request -> {
			if (request.method().equals("POST")) {
				try {
					Thread.sleep(1500);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
			}
			return siteAnswers(request);
		};

		try (KpipaTransport.Connection connection = transport(Duration.ofMillis(300)).open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			assertThatThrownBy(() -> client.fetchRanking(YearMonth.of(2026, 9)))
				.isInstanceOfSatisfying(FetchFailure.class,
						failure -> assertThat(failure.reason()).isEqualTo(FailureReason.NETWORK_ERROR));
			assertThat(client.requestCount()).isEqualTo(2);
		}
	}

	@Test
	void coverIsStoredOnlyWhenItIsAnImage() throws Exception {
		answers = request -> switch (request.path()) {
			case "/files/isbn/s_a.jpg" -> new Answer(200, "image/jpeg", "JPEG", Map.of());
			case "/files/isbn/s_b.jpg" -> new Answer(200, "text/html", "<html>", Map.of());
			default -> new Answer(404, "text/html", "", Map.of());
		};

		try (KpipaTransport.Connection connection = transport(Duration.ofSeconds(5)).open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			assertThat(client.fetchCover("/files/isbn/s_a.jpg")).hasValueSatisfying(cover -> {
				assertThat(cover.contentType()).isEqualTo("image/jpeg");
				assertThat(new String(cover.image(), StandardCharsets.UTF_8)).isEqualTo("JPEG");
			});
			assertThat(client.fetchCover("/files/isbn/s_b.jpg")).isEmpty();
			assertThat(client.fetchCover("/files/isbn/s_c.jpg")).isEmpty();
			assertThat(client.requestCount()).isEqualTo(3);
		}
		assertThat(seen).allSatisfy(request -> assertThat(request.headers()).containsEntry("User-agent", USER_AGENT));
		assertThat(pacer.gaps()).hasSize(2).allSatisfy(gap -> assertThat(gap).isGreaterThanOrEqualTo(Duration.ofSeconds(2)));
	}

	@Test
	void bodyOverTheLimitIsFormatChanged() {
		answers = request -> request.method().equals("GET") ? siteAnswers(request)
				: new Answer(200, "text/html", "x".repeat(70_000), Map.of());

		try (KpipaTransport.Connection connection = new HttpKpipaTransport(URI.create(base), Duration.ofSeconds(5),
				Duration.ofSeconds(5), 65_536, 65_536, USER_AGENT)
			.open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			assertThatThrownBy(() -> client.fetchRanking(YearMonth.of(2026, 9)))
				.isInstanceOfSatisfying(FetchFailure.class,
						failure -> assertThat(failure.reason()).isEqualTo(FailureReason.FORMAT_CHANGED));
		}
	}

	/** Covers have their own limit (user decision 2026-10-12: 50MB); pages keep theirs. */
	@Test
	void coversUseTheCoverLimit() throws Exception {
		answers = request -> new Answer(200, "image/png", "x".repeat(70_000), Map.of());

		try (KpipaTransport.Connection connection = new HttpKpipaTransport(URI.create(base), Duration.ofSeconds(5),
				Duration.ofSeconds(5), 65_536, 100_000, USER_AGENT)
			.open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			assertThat(client.fetchCover("/files/onix/s_big.png")).hasValueSatisfying(
					cover -> assertThat(cover.image()).hasSize(70_000));
		}
		try (KpipaTransport.Connection connection = new HttpKpipaTransport(URI.create(base), Duration.ofSeconds(5),
				Duration.ofSeconds(5), 1_048_576, 65_536, USER_AGENT)
			.open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			assertThat(client.fetchCover("/files/onix/s_big.png")).isEmpty();
		}
	}

	private FetchFailure assertFailure(FailureReason reason, int requests) {
		try (KpipaTransport.Connection connection = transport(Duration.ofSeconds(5)).open()) {
			KpipaClient client = new KpipaClient(connection, pacer, base);
			Throwable thrown = org.assertj.core.api.Assertions.catchThrowable(
					() -> client.fetchRanking(YearMonth.of(2026, 9)));
			assertThat(thrown).isInstanceOf(FetchFailure.class);
			FetchFailure failure = (FetchFailure) thrown;
			assertThat(failure.reason()).isEqualTo(reason);
			assertThat(client.requestCount()).isEqualTo(requests);
			assertThat(seen).hasSize(requests);
			return failure;
		}
	}

	private HttpKpipaTransport transport(Duration timeout) {
		return new HttpKpipaTransport(URI.create(base), timeout, timeout, 1_048_576, 52_428_800, USER_AGENT);
	}

	/** The site as saved on 2026-10-12: rank page with a session cookie, the "전체" list, the novel count. */
	static Answer siteAnswers(Seen request) {
		return switch (request.path()) {
			case "/home/v3/addition/adiStatsCtgSaleRank" -> new Answer(200, "text/html;charset=UTF-8",
					html("rank-page.html"), Map.of("Set-Cookie", "JSESSIONID=SESSION1; Path=/home; HttpOnly"));
			case "/home/v3/addition/adiStatsCtgSaleRank/search" -> new Answer(200, "text/html;charset=UTF-8",
					html("rank-search-all.html"), Map.of());
			case "/home/v3/addition/adiStatsCtgSaleCnt/search" -> new Answer(200, "text/html;charset=UTF-8",
					html("sales-count-novel.html"), Map.of());
			default -> new Answer(404, "text/html", "", Map.of());
		};
	}

	private void handle(HttpExchange exchange) throws IOException {
		java.util.Map<String, String> headers = new java.util.TreeMap<>();
		exchange.getRequestHeaders().forEach((name, values) -> headers.put(name, String.join("; ", values)));
		String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
		Seen request = new Seen(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), headers, body);
		seen.add(request);
		Answer answer = answers.apply(request);
		byte[] bytes = answer.body().getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", answer.contentType());
		answer.headers().forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
		exchange.sendResponseHeaders(answer.status(), bytes.length == 0 ? -1 : bytes.length);
		if (bytes.length > 0) {
			try (OutputStream out = exchange.getResponseBody()) {
				out.write(bytes);
			}
		}
		exchange.close();
	}

	record Seen(String method, String path, Map<String, String> headers, String body) {
	}

	record Answer(int status, String contentType, String body, Map<String, String> headers) {
	}

}
