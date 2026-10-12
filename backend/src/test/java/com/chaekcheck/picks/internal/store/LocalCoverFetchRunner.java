package com.chaekcheck.picks.internal.store;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.util.unit.DataSize;

import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.cover.CoverResizer;
import com.chaekcheck.picks.internal.fetch.HttpNlkTransport;
import com.chaekcheck.picks.internal.fetch.NlkClient;
import com.chaekcheck.picks.internal.fetch.RequestPacer;

/**
 * One-off, local development only (cycle 14, user approval 2026-10-12 "지금 표지만 받기"): fetches the covers of the
 * picks stored in the local {@code db} named in {@code ../.env}, as a changed fetch does (only covers not stored yet,
 * www.nl.go.kr/afile/ only, redirects not followed, 1s apart, 200px JPEG), without the list request and without the
 * API key. Never runs in the build: only with {@code CHAEKCHECK_PICKS_FETCH_LOCAL_COVERS=true}:
 *
 * <pre>
 * CHAEKCHECK_PICKS_FETCH_LOCAL_COVERS=true ./gradlew test --tests '*LocalCoverFetchRunner' --rerun
 * </pre>
 *
 * Writes {@code build/picks-covers.txt}: requests, gaps, failures and sizes.
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_PICKS_FETCH_LOCAL_COVERS", matches = "true")
class LocalCoverFetchRunner {

	@Test
	void fetchMissingCovers() throws IOException, InterruptedException {
		Map<String, String> env = dotEnv(Path.of("..", ".env"));
		DriverManagerDataSource dataSource = new DriverManagerDataSource(
				"jdbc:postgresql://localhost:" + env.getOrDefault("DB_PORT", "5432") + "/" + env.get("DB_NAME"),
				env.get("DB_USERNAME"), env.get("DB_PASSWORD"));
		PicksStore store = new PicksStore(JdbcClient.create(dataSource));
		// the same values as application.yml; no key: covers need none
		PicksProperties.Fetch settings = new PicksProperties.Fetch(true, "", "0 0 5 * * *", ZoneId.of("Asia/Seoul"),
				URI.create("https://nl.go.kr"), Period.ofYears(3), 1000, Duration.ofSeconds(5), Duration.ofSeconds(10),
				DataSize.ofMegabytes(10), DataSize.ofMegabytes(5), Duration.ofSeconds(1), "book search service",
				new PicksProperties.Cover(200, 0.8f, 16_000_000));
		CoverResizer resizer = new CoverResizer(200, 0.8f, 16_000_000);
		RequestPacer pacer = new RequestPacer(settings.requestInterval(), RequestPacer.Ticker.SYSTEM);
		StringBuilder out = new StringBuilder();
		int fetched = 0;
		int failed = 0;
		long bytes = 0;
		Instant start = Instant.now();
		try (HttpNlkTransport transport = new HttpNlkTransport(settings.connectTimeout(), settings.readTimeout(),
				settings.userAgent())) {
			NlkClient client = new NlkClient(transport, pacer, settings);
			List<String> sources = store.missingCoverSources();
			for (String source : sources) {
				Optional<byte[]> original = client.fetchCover(source);
				Optional<byte[]> jpeg = original.flatMap(resizer::toJpeg);
				if (jpeg.isPresent()) {
					store.saveCover(source, CoverResizer.CONTENT_TYPE, jpeg.get(), Instant.now());
					fetched++;
					bytes += jpeg.get().length;
					out.append("ok ").append(source).append(' ').append(original.get().length).append(" -> ")
						.append(jpeg.get().length).append('\n');
				}
				else {
					failed++;
					out.append("FAILED ").append(source).append('\n');
				}
			}
			out.append("requests ").append(client.requestCount()).append(", fetched ").append(fetched)
				.append(", failed ").append(failed).append(", stored bytes ").append(bytes).append(", took ")
				.append(Duration.between(start, Instant.now()).toSeconds()).append("s\n");
			out.append("gaps (ms): ")
				.append(pacer.gaps().stream().mapToLong(Duration::toMillis).summaryStatistics())
				.append('\n');
		}
		Files.writeString(Path.of("build", "picks-covers.txt"), out, StandardCharsets.UTF_8);
	}

	private static Map<String, String> dotEnv(Path path) throws IOException {
		Map<String, String> values = new HashMap<>();
		for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
			int eq = line.indexOf('=');
			if (!line.isBlank() && !line.startsWith("#") && eq > 0) {
				values.put(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
			}
		}
		return values;
	}

}
