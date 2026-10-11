package com.chaekcheck.popular.internal.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.core.io.ClassPathResource;

/**
 * Site answers saved on 2026-10-12 01:40 KST (src/test/resources/fixtures/kpipa, plan cycle 10 step 0) and the
 * hand-reduced files made from them: {@code rank-search-49-books.html} (last book removed),
 * {@code rank-search-no-items.html} (book list removed), {@code rank-search-bad-values.html} (rank 3 ISBN empty,
 * rank 5 ISBN {@code 979-11-123}, rank 7 cover on another host, rank 8 cover path with {@code ..}, rank 9 no image)
 * and {@code sales-count-no-data.html} (first {@code dataX1} empty).
 */
public final class KpipaFixtures {

	private KpipaFixtures() {
	}

	public static String html(String name) {
		try {
			return new ClassPathResource("fixtures/kpipa/" + name).getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	/** A ticker that does not sleep: sleeping moves its clock, each request takes 100ms. */
	public static final class FakeTicker implements RequestPacer.Ticker {

		private long now = 1_000_000_000L;

		private final List<Duration> sleeps = new ArrayList<>();

		@Override
		public long nanoTime() {
			return now;
		}

		@Override
		public void sleep(Duration duration) {
			sleeps.add(duration);
			now += duration.toNanos();
		}

		public void advance(Duration duration) {
			now += duration.toNanos();
		}

		public List<Duration> sleeps() {
			return List.copyOf(sleeps);
		}

	}

}
