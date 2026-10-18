package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ParsedPage;
import com.github.benmanes.caffeine.cache.Ticker;

/**
 * AC-16: 3 minutes after write, with a fake ticker. The two time tests load on the calling thread: with the async
 * executor Caffeine stamps the write time when the load's future completes, which can land after the test has already
 * moved the fake clock, so the entry looked fresh and the test failed now and then (seen 2026-10-17/18 while other work
 * kept the machine busy).
 */
class CaffeineSearchPageCacheTests {

	static final PageKey KEY = new PageKey(2, SearchField.TITLE, "사랑", 1);

	AtomicLong nanos = new AtomicLong();

	Ticker ticker = nanos::get;

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	CaffeineSearchPageCache cache = new CaffeineSearchPageCache(Duration.ofMinutes(3), 1000, executor, ticker);

	AtomicInteger loads = new AtomicInteger();

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void sameKeyWithinThreeMinutesLoadsOnce() {
		CaffeineSearchPageCache cache = sameThreadCache();
		assertThat(cache.get(KEY, loader()).join().hit()).isFalse();
		advance(Duration.ofMinutes(2).plusSeconds(59));
		assertThat(cache.get(KEY, loader()).join().hit()).isTrue();

		assertThat(loads).hasValue(1);
	}

	/** Cycle 08 (ADR-0066): statistics are recorded for the stats log. */
	@Test
	void recordsStatistics() {
		cache.get(KEY, loader()).join();
		cache.get(KEY, loader()).join();
		assertThat(cache.stats().hitCount()).isEqualTo(1);
		assertThat(cache.stats().missCount()).isEqualTo(1);
		assertThat(cache.size()).isEqualTo(1);
		assertThat(cache.cacheName()).isEqualTo("search-page");
	}

	@Test
	void loadsAgainAfterThreeMinutes() {
		CaffeineSearchPageCache cache = sameThreadCache();
		cache.get(KEY, loader()).join();
		advance(Duration.ofMinutes(3).plusSeconds(1));

		assertThat(cache.get(KEY, loader()).join().hit()).isFalse();
		assertThat(loads).hasValue(2);
	}

	@Test
	void differentPagesAreDifferentKeys() {
		cache.get(KEY, loader()).join();
		cache.get(new PageKey(2, SearchField.TITLE, "사랑", 2), loader()).join();
		cache.get(new PageKey(2, SearchField.AUTHOR, "사랑", 1), loader()).join();

		assertThat(loads).hasValue(3);
	}

	@Test
	void failuresAreNotCached() {
		assertThatThrownBy(() -> cache.get(KEY, () -> {
			loads.incrementAndGet();
			throw UpstreamException.error("boom");
		}).join()).hasCauseInstanceOf(UpstreamException.class);

		assertThat(cache.get(KEY, loader()).join().hit()).isFalse();
		assertThat(loads).hasValue(2);
	}

	@Test
	void concurrentRequestsForTheSameKeyShareOneLoad() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		Supplier<FetchedPage> slow = () -> {
			await(release);
			return loader().get();
		};
		List<CompletableFuture<SearchPageCache.Lookup>> lookups = List.of(cache.get(KEY, slow), cache.get(KEY, slow),
				cache.get(KEY, slow));
		release.countDown();

		assertThat(lookups).allSatisfy(f -> assertThat(f.join().page()).isNotNull());
		assertThat(loads).hasValue(1);
	}

	/** Loads run on the caller's thread, so the write time is stamped before the test advances the clock. */
	private CaffeineSearchPageCache sameThreadCache() {
		return new CaffeineSearchPageCache(Duration.ofMinutes(3), 1000, Runnable::run, ticker);
	}

	private Supplier<FetchedPage> loader() {
		return () -> {
			loads.incrementAndGet();
			return new FetchedPage(new ParsedPage(0, List.of()), URI.create("https://example.test"), Instant.now());
		};
	}

	private void advance(Duration duration) {
		nanos.addAndGet(duration.toNanos());
	}

	private static void await(CountDownLatch latch) {
		try {
			latch.await();
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
