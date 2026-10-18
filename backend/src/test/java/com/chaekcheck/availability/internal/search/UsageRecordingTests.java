package com.chaekcheck.availability.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import jakarta.servlet.http.HttpServletRequest;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.ParsedDetail;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.DetailLookupResolver;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.availability.internal.resolve.DetailVerdictCache;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * Cycle 15 step 5 with a fake fetcher (no library site): the direct search is recorded exactly once per stream (AC-3,
 * AC-4), and every library request is counted once by result with one log line (AC-24, plan C5).
 */
@ExtendWith(OutputCaptureExtension.class)
class UsageRecordingTests {

	static final SearchQuery QUERY = SearchQuery.of("채식주의자");

	LibraryPageFetcher fetcher = mock(LibraryPageFetcher.class);

	DetailPageFetcher detailPages = mock(DetailPageFetcher.class);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	RecordingUsageLog usageLog = new RecordingUsageLog();

	LookupOpsCounters counters = new LookupOpsCounters();

	List<Integer> searched = Collections.synchronizedList(new ArrayList<>());

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void firstBatchDoneRecordsTheCountedRowsOnce() { // AC-3
		Sink sink = new Sink(Integer.MAX_VALUE);
		List<Candidate> candidates = List.of(candidate(1), candidate(2));
		candidates.forEach(c -> answer(c, CompletableFuture.completedFuture(page(c, 3, false))));

		runner(settings(20, 2, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 1, candidates, List.of(), null,
				searched::add);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(searched).containsExactly(6);
	}

	@Test
	void noCandidatesIsZeroNotUnknown() {
		Sink sink = new Sink(Integer.MAX_VALUE);

		runner(settings(20, 2, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 1, List.of(), List.of(), null,
				searched::add);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(searched).containsExactly(0);
	}

	@Test
	void clientGoneBeforeBatchDoneRecordsNoCountOnce() { // AC-4
		// the browser is gone after the first event: nothing more reaches it, the search still ends normally
		Sink sink = new Sink(1);
		Candidate c = candidate(1);
		answer(c, CompletableFuture.completedFuture(page(c, 3, false)));

		runner(settings(20, 1, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 1, List.of(c), List.of(), null,
				searched::add);
		await().atMost(Duration.ofSeconds(5)).until(() -> usageLog.answers.size() == 1);
		await().pollDelay(Duration.ofMillis(200)).atMost(Duration.ofSeconds(5)).until(() -> !searched.isEmpty());

		assertThat(searched).containsExactly((Integer) null);
	}

	@Test
	void clientGoneAtBatchDoneRecordsNoCount() { // AC-4: batch-done was not delivered
		// events: library-requested, library-result, then batch-done fails
		Sink sink = new Sink(2);
		Candidate c = candidate(1);
		answer(c, CompletableFuture.completedFuture(page(c, 3, false)));

		runner(settings(20, 1, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 1, List.of(c), List.of(), null,
				searched::add);
		await().atMost(Duration.ofSeconds(5)).until(() -> !searched.isEmpty());
		await().pollDelay(Duration.ofMillis(300)).until(() -> true);

		assertThat(searched).containsExactly((Integer) null);
	}

	@Test
	void clientGoneStartsNoNewLibraryAndSkipsTheRest(CapturedOutput output) { // cycle 20, ADR-0131, AC-24
		// the browser takes the targets event and is gone at the second one (library-requested of candidate 1,
		// whose request is sent anyway)
		Sink sink = new Sink(1);
		Candidate first = candidate(1);
		Candidate second = candidate(2);
		Candidate third = candidate(3);
		CompletableFuture<LibraryPage> inFlight = new CompletableFuture<>();
		answer(first, inFlight);
		answer(second, CompletableFuture.completedFuture(page(second, 1, false)));
		answer(third, CompletableFuture.completedFuture(page(third, 1, false)));

		runner(settings(20, 1, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 1, List.of(first, second, third),
				List.of(), StreamEvents.Targets.of(QUERY, "all", "all", "any", false, List.of()), searched::add);
		// no new library starts: the rest is skipped at once, while the first is still in flight
		await().atMost(Duration.ofSeconds(5)).until(() -> usageLog.answers.size() == 2);
		assertThat(usageLog.answers).containsExactlyInAnyOrder("2 DIRECT_SEARCH SKIPPED", "3 DIRECT_SEARCH SKIPPED");
		// the request already sent is still received (and fills the cache)
		inFlight.complete(page(first, 1, false));
		await().atMost(Duration.ofSeconds(5)).until(() -> usageLog.answers.size() == 3);

		assertThat(usageLog.answers).contains("1 DIRECT_SEARCH OK");
		await().atMost(Duration.ofSeconds(5)).until(() -> output.getOut().contains("stopReason=CLIENT_GONE"));
		assertThat(searched).containsExactly((Integer) null);
	}

	@Test
	void emitterErrorMarksTheClientGoneWithoutAFailedSend() { // cycle 20: onError / onTimeout path
		Sink sink = new Sink(Integer.MAX_VALUE);
		Candidate first = candidate(1);
		Candidate second = candidate(2);
		CompletableFuture<LibraryPage> inFlight = new CompletableFuture<>();
		answer(first, inFlight);
		answer(second, CompletableFuture.completedFuture(page(second, 1, false)));

		runner(settings(20, 1, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 2, List.of(first, second), List.of(),
				null, null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.sent >= 1);
		sink.clientGone.run();
		inFlight.complete(page(first, 1, false));
		await().atMost(Duration.ofSeconds(5)).until(() -> usageLog.answers.size() == 2);

		assertThat(usageLog.answers).containsExactlyInAnyOrder("1 DIRECT_SEARCH OK", "2 DIRECT_SEARCH SKIPPED");
		// the stream is not completed for a browser that left; the slot still goes back
		assertThat(sink.completed).isFalse();
	}

	@Test
	void laterBatchesRecordNoSearch() {
		Sink sink = new Sink(Integer.MAX_VALUE);
		Candidate c = candidate(1);
		answer(c, CompletableFuture.completedFuture(page(c, 3, false)));

		runner(settings(20, 1, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 2, List.of(c), List.of(), null, null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(searched).isEmpty();
		assertThat(usageLog.answers).containsExactly("1 DIRECT_SEARCH OK");
	}

	@Test
	void eachRequestIsCountedByItsResult(CapturedOutput output) { // AC-24
		Sink sink = new Sink(Integer.MAX_VALUE);
		Candidate ok = candidate(1);
		Candidate error = candidate(2);
		Candidate timeout = candidate(3);
		Candidate cached = candidate(4);
		answer(ok, CompletableFuture.completedFuture(page(ok, 1, false)));
		answer(error, CompletableFuture.failedFuture(UpstreamException.error("HTTP 500")));
		answer(timeout, CompletableFuture.failedFuture(UpstreamException.timeout("read timed out")));
		answer(cached, CompletableFuture.completedFuture(page(cached, 1, true)));

		runner(settings(20, 4, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 2,
				List.of(ok, error, timeout, cached), List.of(), null, null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		// the cached answer counts only as CACHED and writes no line (cycle 19, ADR-0116)
		assertThat(usageLog.answers).containsExactlyInAnyOrder("1 DIRECT_SEARCH OK", "2 DIRECT_SEARCH FAILED",
				"3 DIRECT_SEARCH TIMEOUT", "4 DIRECT_SEARCH CACHED");
		assertThat(output).containsPattern("library=1 source=direct_search result=ok ms=\\d+")
			.containsPattern("library=2 source=direct_search result=failed ms=\\d+ error=HTTP 500")
			.containsPattern("library=3 source=direct_search result=timeout ms=\\d+")
			.doesNotContain("library=4 source=");
	}

	@Test
	void answerOverTheBodyLimitIsBodyTooLargeWithItsOwnLine(CapturedOutput output) { // cycle 20, AC-34
		Sink sink = new Sink(Integer.MAX_VALUE);
		Candidate big = candidate(5);
		Candidate ok = candidate(6);
		answer(big, CompletableFuture.failedFuture(UpstreamException.bodyTooLarge(
				java.net.URI.create("https://lib5.test/search?q=x"), 1024, 1500, null)));
		answer(ok, CompletableFuture.completedFuture(page(ok, 1, false)));

		runner(settings(20, 2, 3000, 1000)).start(sink, QUERY, ResultFilter.ALL, 2, List.of(big, ok), List.of(), null,
				null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(usageLog.answers).containsExactlyInAnyOrder("5 DIRECT_SEARCH BODY_TOO_LARGE", "6 DIRECT_SEARCH OK");
		assertThat(output).containsPattern("body_too_large library=5 url=https://lib5.test/search\\?q=x receivedBytes=1500 at=\\S+")
			.containsPattern("library=5 source=direct_search result=body_too_large ms=\\d+ error=");
		LookupOpsCounters.Snapshot counts = counters.drain();
		assertThat(counts.bodyTooLarge()).isEqualTo(1);
		assertThat(counts.libraryRequests()).isEqualTo(2);
	}

	@Test
	void answerAfterTheBatchDeadlineIsATimeoutAndNotSentIsSkipped() { // plan C5
		Sink sink = new Sink(Integer.MAX_VALUE);
		Candidate slow = candidate(1);
		Candidate waiting = candidate(2);
		CompletableFuture<LibraryPage> late = new CompletableFuture<>();
		answer(slow, late);
		answer(waiting, CompletableFuture.completedFuture(page(waiting, 1, false)));

		// one at a time, deadline 600ms, no launch in the last 500ms: the second never starts
		runner(settings(20, 1, 600, 500)).start(sink, QUERY, ResultFilter.ALL, 2, List.of(slow, waiting), List.of(),
				null, null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);
		assertThat(usageLog.answers).containsExactly("2 DIRECT_SEARCH SKIPPED");

		late.complete(page(slow, 1, false));

		assertThat(usageLog.answers).containsExactly("2 DIRECT_SEARCH SKIPPED", "1 DIRECT_SEARCH TIMEOUT");
	}

	@Test
	void holdingRequestsAreCountedAsHoldingFill() { // AC-17 part: not a book view
		Sink sink = new Sink(Integer.MAX_VALUE);
		SearchTarget library = TestTargets.target(1, PlatformType.KYOBO_V1, "https://lib1.test");
		HoldingTask task = HoldingTask.of(library, new HoldingRef(11, 7, 1, "KYOBO_V1",
				"https://lib1.test/detail?id=7", "책", "저자", "출판사"));
		HoldingTask cachedTask = HoldingTask.of(library, new HoldingRef(12, 8, 1, "KYOBO_V1",
				"https://lib1.test/detail?id=8", "책", "저자", "출판사"));
		HoldingTask offHost = new HoldingTask(library, new HoldingRef(13, 9, 1, "KYOBO_V1", "https://evil.test/x",
				"책", "저자", "출판사"), null);
		given(detailPages.fetch(eq(library), eq(task.detailUri()), any())).willReturn(CompletableFuture
			.completedFuture(new DetailVerdictCache.DetailPage(ParsedDetail.counted(2, 1, 0), Instant.now().plusSeconds(1))));
		given(detailPages.fetch(eq(library), eq(cachedTask.detailUri()), any())).willReturn(CompletableFuture
			.completedFuture(new DetailVerdictCache.DetailPage(ParsedDetail.counted(2, 1, 0), Instant.EPOCH)));

		runner(settings(20, 1, 3000, 1000)).startHoldings(sink, ResultFilter.ALL, 1, List.of(task, cachedTask, offHost),
				List.of(), null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(usageLog.answers).containsExactlyInAnyOrder("1 HOLDING_FILL OK", "1 HOLDING_FILL CACHED");
		assertThat(usageLog.bookViews).isZero();
	}

	private BatchRunner runner(AvailabilityProperties.Batch settings) {
		return new BatchRunner(mock(LibraryCatalog.class), fetcher, detailPages,
				new AvailabilityResolvers(mock(DetailLookupResolver.class)), settings, Duration.ofSeconds(3),
				Duration.ZERO, executor, new SearchSlots(4), Clock.systemUTC(), usageLog, counters);
	}

	private static AvailabilityProperties.Batch settings(int rowLimit, int parallelism, long deadlineMillis,
			long cutoffMillis) {
		return new AvailabilityProperties.Batch(rowLimit, parallelism, Duration.ofMillis(deadlineMillis),
				Duration.ofMillis(cutoffMillis), 50, 50);
	}

	private void answer(Candidate candidate, CompletableFuture<LibraryPage> page) {
		given(fetcher.fetchAsync(eq(candidate.target()), eq(QUERY), eq(candidate.page()), any())).willReturn(page);
	}

	private static Candidate candidate(long id) {
		return new Candidate(TestTargets.target(id, PlatformType.KYOBO_V1, "https://lib" + id + ".test"), 1);
	}

	private static LibraryPage page(Candidate candidate, int rows, boolean cached) {
		List<LibraryPage.Item> items = new ArrayList<>();
		for (int i = 1; i <= rows; i++) {
			items.add(new LibraryPage.Item(i, "t" + i, null, null, null, 1, 0, 0, 1, "t" + i, null, null, null, null));
		}
		return new LibraryPage(candidate.target().id(), QUERY.value(), 1, 10, rows, false, Instant.now(), cached, 0,
				null, "https://x", items);
	}

	/** Accepts the first {@code delivered} events, then reports the browser gone. */
	static final class Sink implements EventSink {

		private final int delivered;

		volatile int sent;

		volatile boolean completed;

		/** What the session registered (cycle 20): the emitter's error / timeout path. */
		volatile Runnable clientGone = () -> {
		};

		Sink(int delivered) {
			this.delivered = delivered;
		}

		@Override
		public synchronized boolean send(String name, Object data) {
			return sent++ < delivered;
		}

		@Override
		public void complete() {
			completed = true;
		}

		@Override
		public void onClientGone(Runnable listener) {
			clientGone = listener;
		}

	}

	static final class RecordingUsageLog implements UsageLog {

		final List<String> answers = Collections.synchronizedList(new ArrayList<>());

		volatile int bookViews;

		@Override
		public UsageRequest capture(HttpServletRequest request) {
			return UsageRequest.NOT_RECORDED;
		}

		@Override
		public void recordSearch(UsageRequest request, SearchKind kind, SearchConditions conditions,
				Integer resultCount) {
		}

		@Override
		public void recordBookView(UsageRequest request, long bookId, List<Long> residenceRegionIds,
				List<Long> workRegionIds, List<Long> libraryIds) {
			bookViews++;
		}

		@Override
		public void recordMore(UsageRequest request, MoreKind kind, int seq, Long libraryId,
				SearchConditions conditions) {
		}

		@Override
		public void recordLibraryResponse(long libraryId, ResponseSource source, ResponseResult result,
				long elapsedMs) {
			answers.add(libraryId + " " + source + " " + result);
		}

	}

}
