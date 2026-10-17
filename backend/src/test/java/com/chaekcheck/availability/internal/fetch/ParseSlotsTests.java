package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.egress.TestLanes;
import com.chaekcheck.availability.internal.adapter.ParsedPage;
import com.chaekcheck.availability.internal.adapter.PlatformAdapter;
import com.chaekcheck.availability.internal.title.BookKeyNormalizer;
import com.chaekcheck.library.PlatformType;
import com.github.benmanes.caffeine.cache.Ticker;

/** The parse slots (cycle 20, overall review #4, AC-33 server part): at most 8 parses at once, the 9th waits 2s. */
class ParseSlotsTests {

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void ninthPageWaitsTheFullWaitThenFailsAsParseBusyAndNoMoreThanEightParseAtOnce() throws Exception {
		CountDownLatch release = new CountDownLatch(1);
		AtomicInteger parsing = new AtomicInteger();
		AtomicInteger maxParsing = new AtomicInteger();
		AtomicInteger parsed = new AtomicInteger();
		PlatformAdapter adapter = mock(PlatformAdapter.class);
		given(adapter.platform()).willReturn(PlatformType.KYOBO_V1);
		given(adapter.pageSize()).willReturn(10);
		given(adapter.searchUri(any(), any(), anyInt())).willAnswer(call -> URI.create(call.getArgument(0) + "/s"));
		given(adapter.librarySearchUri(any(), any(), any())).willReturn(URI.create("https://x"));
		given(adapter.parse(any(), any())).willAnswer(call -> {
			maxParsing.accumulateAndGet(parsing.incrementAndGet(), Math::max);
			try {
				release.await();
			}
			finally {
				parsing.decrementAndGet();
				parsed.incrementAndGet();
			}
			return new ParsedPage(0, List.of());
		});
		SiteFetcher site = mock(SiteFetcher.class);
		given(site.fetch(any(), any(), any()))
			.willReturn(new SiteResponse(200, "text/html", null, "<html/>".getBytes(StandardCharsets.UTF_8), "test"));
		ParseSlots slots = new ParseSlots(8, Duration.ofSeconds(2));
		LibraryPageFetcher fetcher = new LibraryPageFetcher(List.of(adapter),
				new CaffeineSearchPageCache(Duration.ofMinutes(3), 1000, executor, Ticker.systemTicker()),
				TestLanes.direct(site, Clock.systemUTC()), Duration.ofSeconds(10),
				Clock.systemUTC(), new BookKeyNormalizer(), slots, new InMemoryHostQueueBoard());

		List<CompletableFuture<LibraryPage>> first = new ArrayList<>();
		for (int i = 1; i <= 8; i++) {
			first.add(fetcher.fetchAsync(TestTargets.target(i, PlatformType.KYOBO_V1, "https://lib" + i + ".test"),
					SearchQuery.of("사랑"), 1));
		}
		await().atMost(Duration.ofSeconds(5)).until(() -> slots.inUse() == 8);

		long start = System.nanoTime();
		UpstreamException ex = catchThrowableOfType(UpstreamException.class, () -> fetcher
			.fetch(TestTargets.target(9, PlatformType.KYOBO_V1, "https://lib9.test"), SearchQuery.of("사랑"), 1));
		long waitedMillis = (System.nanoTime() - start) / 1_000_000;

		// the 9th waited the whole 2s and failed alone with parse_busy; it never parsed
		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
		assertThat(ex.code()).isEqualTo(ParseSlots.PARSE_BUSY);
		assertThat(waitedMillis).isBetween(1800L, 4000L);
		assertThat(parsing.get()).isEqualTo(8);

		// the eight finish normally
		release.countDown();
		CompletableFuture.allOf(first.toArray(CompletableFuture[]::new)).get();
		assertThat(first).allSatisfy(f -> assertThat(f.join().libraryId()).isPositive());
		assertThat(maxParsing.get()).isEqualTo(8);
		assertThat(parsed.get()).isEqualTo(8);
		assertThat(slots.inUse()).isZero();
		assertThat(slots.drainMaxInUse()).isEqualTo(8);

		// nothing was cached for the failed library: the next request loads again, and now gets a slot
		LibraryPage page = fetcher.fetch(TestTargets.target(9, PlatformType.KYOBO_V1, "https://lib9.test"),
				SearchQuery.of("사랑"), 1);
		assertThat(page.cached()).isFalse();
		assertThat(parsed.get()).isEqualTo(9);
	}

	@Test
	void parseBusyIsA503ProblemWithItsOwnCode() {
		UpstreamException ex = UpstreamException.parseBusy("no slot");

		assertThat(com.chaekcheck.availability.internal.Problems.upstream(ex).getStatus()).isEqualTo(503);
		assertThat(com.chaekcheck.availability.internal.Problems.upstream(ex).getProperties())
			.containsEntry("code", "parse_busy");
	}

	@Test
	void slotIsGivenBackWhenTheParseThrows() {
		ParseSlots slots = new ParseSlots(1, Duration.ofMillis(100));

		catchThrowableOfType(IllegalStateException.class, () -> slots.parse(() -> {
			throw new IllegalStateException("bad page");
		}));

		assertThat(slots.inUse()).isZero();
		assertThat(slots.parse(() -> "ok")).isEqualTo("ok");
	}

}
