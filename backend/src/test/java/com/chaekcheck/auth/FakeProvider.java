package com.chaekcheck.auth;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Fake Naver and Kakao endpoints for the auth tests, on the JDK's own HTTP server (no extra test dependency): token,
 * profile, and unlink/revoke. Each test sets what they answer; {@link #reset()} goes back to the defaults.
 */
final class FakeProvider {

	static final String NAVER_ACCESS_TOKEN = "fake-naver-access-token-5b1f";

	static final String KAKAO_ACCESS_TOKEN = "fake-kakao-access-token-9c2e";

	final HttpServer server;

	final List<String> tokenRequests = new CopyOnWriteArrayList<>();

	volatile int tokenStatus;

	volatile long profileDelayMillis;

	volatile String naverUser;

	volatile String kakaoUser;

	volatile int unlinkStatus;

	/** Unlink and revoke calls: "path|Authorization header|form body". */
	final List<String> unlinkRequests = new CopyOnWriteArrayList<>();

	private FakeProvider(HttpServer server) {
		this.server = server;
		reset();
	}

	static FakeProvider start() {
		try {
			HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
			FakeProvider fake = new FakeProvider(server);
			server.createContext("/naver/token", exchange -> fake.token(exchange, NAVER_ACCESS_TOKEN));
			server.createContext("/kakao/token", exchange -> fake.token(exchange, KAKAO_ACCESS_TOKEN));
			server.createContext("/naver/me", exchange -> fake.profile(exchange, fake.naverUser));
			server.createContext("/kakao/me", exchange -> fake.profile(exchange, fake.kakaoUser));
			server.createContext("/naver/unlink", fake::unlink);
			server.createContext("/kakao/unlink", fake::unlink);
			server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
			server.start();
			return fake;
		}
		catch (IOException ex) {
			throw new IllegalStateException(ex);
		}
	}

	void reset() {
		tokenStatus = 200;
		profileDelayMillis = 0;
		naverUser = "{\"resultcode\":\"00\",\"message\":\"success\",\"response\":{\"id\":\"naver-default\"}}";
		kakaoUser = "{\"id\":1}";
		tokenRequests.clear();
		unlinkStatus = 200;
		unlinkRequests.clear();
	}

	String url(String path) {
		return "http://127.0.0.1:" + server.getAddress().getPort() + path;
	}

	private void token(HttpExchange exchange, String accessToken) throws IOException {
		tokenRequests.add(URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(),
				StandardCharsets.UTF_8), StandardCharsets.UTF_8));
		if (tokenStatus != 200) {
			send(exchange, tokenStatus, "{\"error\":\"server_error\"}");
			return;
		}
		send(exchange, 200, "{\"access_token\":\"" + accessToken
				+ "\",\"token_type\":\"bearer\",\"expires_in\":3600,\"refresh_token\":\"fake-provider-refresh\"}");
	}

	private void unlink(HttpExchange exchange) throws IOException {
		unlinkRequests.add(exchange.getRequestURI().getPath() + "|"
				+ exchange.getRequestHeaders().getFirst("Authorization") + "|"
				+ URLDecoder.decode(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8),
						StandardCharsets.UTF_8));
		send(exchange, unlinkStatus, unlinkStatus == 200 ? "{\"id\":1}" : "{\"msg\":\"error\"}");
	}

	private void profile(HttpExchange exchange, String body) throws IOException {
		if (profileDelayMillis > 0) {
			try {
				Thread.sleep(profileDelayMillis);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
		}
		send(exchange, 200, body);
	}

	private static void send(HttpExchange exchange, int status, String body) throws IOException {
		byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
		exchange.sendResponseHeaders(status, bytes.length);
		try (OutputStream out = exchange.getResponseBody()) {
			out.write(bytes);
		}
		catch (IOException ex) {
			// the client gave up (timeout case)
		}
	}

}
