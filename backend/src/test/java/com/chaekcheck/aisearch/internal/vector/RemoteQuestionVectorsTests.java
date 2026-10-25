package com.chaekcheck.aisearch.internal.vector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import com.chaekcheck.aisearch.internal.config.AiSearchProperties;

/**
 * {@link KureQuestionVectors} with {@code encoder: remote} against a stand-in embedding server: the question goes in
 * the body with the token header, the answer's vector finds the nearest books held here, and a wrong token, a server
 * error or no answer within the timeout fail the lookup (the search then goes on with keywords).
 */
class RemoteQuestionVectorsTests {

	static final String TOKEN = "test-embed-token";

	@TempDir
	Path dir;

	HttpServer server;

	final List<String> bodies = new CopyOnWriteArrayList<>();

	volatile int status = 200;

	volatile long delayMillis;

	@BeforeEach
	void start() throws IOException {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext(RemoteQuestionEncoder.PATH, exchange -> {
			bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
			try {
				Thread.sleep(delayMillis);
			}
			catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
			boolean allowed = TOKEN.equals(exchange.getRequestHeaders().getFirst(RemoteQuestionEncoder.TOKEN_HEADER));
			byte[] answer = "{\"vector\":[0.0,1.0]}".getBytes(StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(allowed ? status : 401, allowed && status == 200 ? answer.length : -1);
			if (allowed && status == 200) {
				exchange.getResponseBody().write(answer);
			}
			exchange.close();
		});
		server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
		server.start();
		writeBooks();
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void theServersVectorFindsTheNearestBooksHere() throws Exception {
		try (KureQuestionVectors vectors = vectors(TOKEN, Duration.ofSeconds(2))) {
			assertThat(vectors.on()).isTrue();
			assertThat(vectors.nearest("잠들기 전에 읽을 과학책", 2).get(5, TimeUnit.SECONDS)).containsExactly(20L, 30L);
			assertThat(bodies).containsExactly("{\"text\":\"잠들기 전에 읽을 과학책\"}");
		}
	}

	@Test
	void aWrongTokenAServerErrorOrALateAnswerFailTheLookup() throws Exception {
		try (KureQuestionVectors vectors = vectors("wrong", Duration.ofSeconds(2))) {
			assertThatThrownBy(() -> vectors.nearest("질문", 2).get(5, TimeUnit.SECONDS))
				.hasMessageContaining("answered 401");
		}
		status = 500;
		try (KureQuestionVectors vectors = vectors(TOKEN, Duration.ofSeconds(2))) {
			assertThatThrownBy(() -> vectors.nearest("질문", 2).get(5, TimeUnit.SECONDS))
				.hasMessageContaining("answered 500");
		}
		status = 200;
		delayMillis = 800;
		try (KureQuestionVectors vectors = vectors(TOKEN, Duration.ofMillis(200))) {
			assertThatThrownBy(() -> vectors.nearest("질문", 2).get(5, TimeUnit.SECONDS))
				.hasMessageContaining("did not answer");
		}
	}

	@Test
	void noAddressLeavesTheVectorsOff() {
		AiSearchProperties.Remote none = new AiSearchProperties.Remote("", TOKEN, Duration.ofSeconds(1));
		try (KureQuestionVectors vectors = new KureQuestionVectors(properties(none), JsonMapper.builder().build())) {
			assertThat(vectors.on()).isFalse();
		}
	}

	private KureQuestionVectors vectors(String token, Duration timeout) {
		AiSearchProperties.Remote remote = new AiSearchProperties.Remote(
				"http://127.0.0.1:" + server.getAddress().getPort() + "/", token, timeout);
		return new KureQuestionVectors(properties(remote), JsonMapper.builder().build());
	}

	private AiSearchProperties properties(AiSearchProperties.Remote remote) {
		return new AiSearchProperties(true, java.time.ZoneId.of("Asia/Seoul"), Duration.ofSeconds(5),
				Duration.ofSeconds(15), 512, 3072, 20, new AiSearchProperties.Rate(10, 100),
				new AiSearchProperties.Budget(java.math.BigDecimal.TWO),
				new AiSearchProperties.Price(java.math.BigDecimal.ONE, java.math.BigDecimal.ONE),
				new AiSearchProperties.Limits(30, 8, 5, 200, 300),
				new AiSearchProperties.Vector(true, dir.toString(), 2, 50, 60, 60, Duration.ofSeconds(2),
						AiSearchProperties.Encoder.REMOTE, remote));
	}

	/** Three books in 2 dimensions: 10 along x, 20 along y, 30 between them (the model files are not needed). */
	private void writeBooks() throws IOException {
		long[] ids = { 10, 20, 30 };
		float[] scales = { 1 / 127f, 1 / 127f, 0.7071f / 127 };
		byte[] rows = { 127, 0, 0, 127, 127, 127 };
		ByteBuffer buffer = ByteBuffer.allocate(6 + 8 + ids.length * 12 + rows.length).order(ByteOrder.LITTLE_ENDIAN);
		buffer.put("CCBV1\n".getBytes(StandardCharsets.US_ASCII)).putInt(ids.length).putInt(2);
		for (long id : ids) {
			buffer.putLong(id);
		}
		for (float scale : scales) {
			buffer.putFloat(scale);
		}
		buffer.put(rows);
		Files.write(dir.resolve(BookVectorFile.NAME), buffer.array());
	}

}
