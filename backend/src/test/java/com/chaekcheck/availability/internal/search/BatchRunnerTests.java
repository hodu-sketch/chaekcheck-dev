package com.chaekcheck.availability.internal.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPage.Availability;
import com.chaekcheck.availability.internal.fetch.LibraryPage.AvailabilityBasis;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver.Decision;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.DetailLookupResolver;
import com.chaekcheck.availability.internal.resolve.DetailPageFetcher;
import com.chaekcheck.availability.internal.adapter.ParsedDetail;
import com.chaekcheck.availability.internal.resolve.DetailVerdictCache;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.UsageLog;

/** Batch rules with a fake fetcher (design-delta "묶음 실행 규칙"). */
class BatchRunnerTests {

	static final SearchQuery QUERY = SearchQuery.of("사랑");

	static final Duration HOST_INTERVAL = Duration.ofMillis(200);

	LibraryPageFetcher fetcher = mock(LibraryPageFetcher.class);

	DetailLookupResolver detailLookup = mock(DetailLookupResolver.class);

	DetailPageFetcher detailPages = mock(DetailPageFetcher.class);

	ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

	RecordingSink sink = new RecordingSink();

	SearchSlots slots = new SearchSlots(4);

	@AfterEach
	void close() {
		executor.close();
	}

	@Test
	void batchOverTheSearchCapIsRefusedAtOnceAndSendsNothing() {
		slots = new SearchSlots(1);
		BatchRunner runner = runner(settings(20, 1, 8000, 3000));
		Candidate held = new Candidate(kyobo(1), 1);
		CompletableFuture<LibraryPage> answer = new CompletableFuture<>();
		given(fetcher.fetchAsync(eq(held.target()), eq(QUERY), eq(held.page()), any())).willReturn(answer);
		runner.start(sink, QUERY, ResultFilter.ALL, 1, List.of(held), List.of(), null, null);
		await().until(() -> sink.count(StreamEvents.LIBRARY_REQUESTED) == 1);

		RecordingSink refused = new RecordingSink();
		assertThatThrownBy(() -> runner.start(refused, QUERY, ResultFilter.ALL, 1,
				List.of(new Candidate(kyobo(2), 1)), List.of(), null, null))
			.isInstanceOf(SearchBusyException.class);
		assertThatThrownBy(() -> runner.startHoldings(refused, ResultFilter.ALL, 1, List.of(), List.of(), null))
			.isInstanceOf(SearchBusyException.class);
		assertThat(refused.events).isEmpty();
		verify(fetcher, never()).fetchAsync(eq(kyobo(2)), any(), anyInt(), any());

		// the running batch ends: its slot is free again and a new batch runs
		answer.complete(page(held, 1, 1, 0));
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed && slots.available() == 1);
		RecordingSink next = new RecordingSink();
		runner.startHoldings(next, ResultFilter.ALL, 2, List.of(), List.of(), null);
		await().atMost(Duration.ofSeconds(5)).until(() -> next.completed && slots.available() == 1);
	}

	@Test
	void stopsStartingRequestsOnceMoreThanTwentyRowsAreCounted() { // AC-1, AC-2
		List<Candidate> candidates = kyoboCandidates(8);
		candidates.forEach(c -> answer(c, page(c, 10, 10, 0)));

		run(runner(settings(20, 1, 8000, 3000)), ResultFilter.ALL, candidates);

		assertThat(sink.names()).filteredOn(StreamEvents.LIBRARY_REQUESTED::equals).hasSize(3);
		assertThat(sink.batchDone()).extracting(StreamEvents.BatchDone::requested, StreamEvents.BatchDone::countedRows,
				StreamEvents.BatchDone::stopReason)
			.containsExactly(3, 30, StreamEvents.StopReason.ROW_LIMIT);
	}

	@Test
	void requestsAlreadyStartedAreStillReceived() {
		List<Candidate> candidates = kyoboCandidates(4);
		List<CompletableFuture<LibraryPage>> futures = candidates.stream().map(c -> {
			CompletableFuture<LibraryPage> f = new CompletableFuture<>();
			given(fetcher.fetchAsync(eq(c.target()), eq(QUERY), eq(c.page()), any())).willReturn(f);
			return f;
		}).toList();

		// row limit 5: the first answer (10 rows) stops new requests; the two others already started still arrive
		run(runner(settings(5, 3, 8000, 3000)), ResultFilter.ALL, candidates, () -> {
			await().until(() -> sink.count(StreamEvents.LIBRARY_REQUESTED) == 3);
			futures.get(0).complete(page(candidates.get(0), 10, 10, 0));
			futures.get(1).complete(page(candidates.get(1), 10, 10, 0));
			futures.get(2).complete(page(candidates.get(2), 10, 10, 0));
		});

		assertThat(sink.count(StreamEvents.LIBRARY_REQUESTED)).isEqualTo(3);
		assertThat(sink.count(StreamEvents.LIBRARY_RESULT)).isEqualTo(3);
		assertThat(sink.batchDone()).extracting(StreamEvents.BatchDone::countedRows, StreamEvents.BatchDone::stopReason)
			.containsExactly(30, StreamEvents.StopReason.ROW_LIMIT);
	}

	@Test
	void filterAvailableCountsOnlyAvailableRows() {
		List<Candidate> candidates = kyoboCandidates(4);
		candidates.forEach(c -> answer(c, page(c, 10, 1, 1))); // holdings 1, loaned 1: RESERVABLE

		run(runner(settings(20, 1, 8000, 3000)), ResultFilter.AVAILABLE, candidates);

		assertThat(sink.batchDone()).extracting(StreamEvents.BatchDone::requested, StreamEvents.BatchDone::countedRows,
				StreamEvents.BatchDone::stopReason)
			.containsExactly(4, 0, StreamEvents.StopReason.CANDIDATES_EXHAUSTED);
	}

	@Test
	void candidateOrderIsRequestOrderAndPagesPassThrough() { // AC-5, AC-8, AC-9
		List<Candidate> candidates = List.of(new Candidate(kyobo(5), 2), new Candidate(kyobo(3), 1),
				new Candidate(kyobo(9), 3));
		candidates.forEach(c -> answer(c, page(c, 1, 1, 0)));

		run(runner(settings(20, 1, 8000, 3000)), ResultFilter.ALL, candidates);

		assertThat(sink.dataOf(StreamEvents.LIBRARY_REQUESTED)).containsExactly(
				new StreamEvents.LibraryRequested(2, 5, 2), new StreamEvents.LibraryRequested(2, 3, 1),
				new StreamEvents.LibraryRequested(2, 9, 3));
		verify(fetcher).fetchAsync(eq(kyobo(5)), eq(QUERY), eq(2), any());
	}

	@Test
	void firstRequestIsPageOne() { // AC-3: page size comes from the adapter (10)
		Candidate candidate = new Candidate(kyobo(1), 1);
		answer(candidate, page(candidate, 10, 1, 0));

		run(runner(settings(20, 1, 8000, 3000)), ResultFilter.ALL, List.of(candidate));

		verify(fetcher).fetchAsync(eq(kyobo(1)), eq(QUERY), eq(1), any());
	}

	@Test
	void libraryWithoutAnswerByTheDeadlineFailsAlone() { // AC-7
		Candidate slow = new Candidate(kyobo(1), 1);
		Candidate fast = new Candidate(kyobo(2), 1);
		given(fetcher.fetchAsync(eq(slow.target()), any(), anyInt(), any())).willReturn(new CompletableFuture<>());
		answer(fast, page(fast, 2, 1, 0));

		run(runner(settings(20, 2, 400, 100)), ResultFilter.ALL, List.of(slow, fast));

		List<Object> failed = sink.dataOf(StreamEvents.LIBRARY_FAILED);
		assertThat(failed).hasSize(1);
		assertThat((StreamEvents.LibraryFailed) failed.getFirst()).satisfies(f -> {
			assertThat(f.libraryId()).isEqualTo(1);
			assertThat(f.problem()).containsEntry("code", "upstream_timeout");
		});
		assertThat(sink.count(StreamEvents.LIBRARY_RESULT)).isEqualTo(1);
	}

	@Test
	void underscoreHostLibraryThatFailsFailsAlone() { // cycle 18 C4: certificate or timeout failure on the new path
		Candidate underscore = new Candidate(
				TestTargets.target(2624, PlatformType.YES24_V2, "https://hs_partners.yes24library.com"), 1);
		Candidate other = new Candidate(kyobo(2), 1);
		given(fetcher.fetchAsync(eq(underscore.target()), any(), anyInt(), any()))
			.willReturn(CompletableFuture.failedFuture(UpstreamException.error("certificate name check failed")));
		answer(other, page(other, 2, 1, 0));

		run(runner(settings(20, 2, 1000, 300)), ResultFilter.ALL, List.of(underscore, other));

		List<Object> failed = sink.dataOf(StreamEvents.LIBRARY_FAILED);
		assertThat(failed).hasSize(1);
		assertThat((StreamEvents.LibraryFailed) failed.getFirst()).satisfies(f -> {
			assertThat(f.libraryId()).isEqualTo(2624);
			assertThat(f.problem()).containsEntry("code", "upstream_error");
		});
		assertThat(sink.count(StreamEvents.LIBRARY_RESULT)).isEqualTo(1);
		assertThat(sink.batchDone()).isNotNull();
	}

	@Test
	void noRequestStartsWhenTooLittleTimeIsLeft() {
		List<Candidate> candidates = kyoboCandidates(2);

		run(runner(settings(20, 1, 300, 300)), ResultFilter.ALL, candidates);

		assertThat(sink.count(StreamEvents.LIBRARY_REQUESTED)).isZero();
		assertThat(sink.batchDone().stopReason()).isEqualTo(StreamEvents.StopReason.LAUNCH_CUTOFF);
	}

	@Test
	void eventOrderWithRowVerdictsBeforeDone() { // AC-15
		SearchTarget portal = TestTargets.target(12, PlatformType.ELIB_PORTAL_V1, "https://www.gbelib.kr/elib");
		Candidate portalCandidate = new Candidate(portal, 1);
		Candidate kyoboCandidate = new Candidate(kyobo(2), 1);
		answer(portalCandidate, new LibraryPage(12, "사랑", 1, 10, 2, false, Instant.now(), false, 0, null, "https://x",
				List.of(portalRow(1, 0), portalRow(2, 5))));
		answer(kyoboCandidate, page(kyoboCandidate, 1, 1, 0));
		CompletableFuture<Decision> detail = new CompletableFuture<>();
		given(detailLookup.lookup(any(), any(), any())).willReturn(detail);

		run(runner(settings(20, 1, 8000, 3000)), ResultFilter.ALL, List.of(portalCandidate, kyoboCandidate), () -> {
			await().until(() -> sink.count(StreamEvents.BATCH_DONE) == 1);
			assertThat(sink.count(StreamEvents.DONE)).isZero();
			detail.complete(new Decision(Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL));
		});

		assertThat(sink.names()).containsExactly("targets", "library-requested", "library-result",
				"library-requested", "library-result", "batch-done", "row-verdict", "done");
		assertThat(sink.batchDone()).extracting(StreamEvents.BatchDone::countedRows, StreamEvents.BatchDone::pendingRows)
			.containsExactly(2, 1);
		assertThat(sink.dataOf(StreamEvents.ROW_VERDICT)).containsExactly(new StreamEvents.RowVerdict(2, 12, 1, 2,
				Availability.RESERVABLE, AvailabilityBasis.SITE_DETAIL));
	}

	@Test
	void missingLibrariesFailOnTheirOwn() {
		Candidate ok = new Candidate(kyobo(1), 1);
		answer(ok, page(ok, 1, 1, 0));

		BatchRunner runner = runner(settings(20, 1, 8000, 3000));
		runner.start(sink, QUERY, ResultFilter.ALL, 2, List.of(ok), List.of(new BatchRunner.MissingCandidate(7, 2)),
				null, null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		StreamEvents.LibraryFailed failed = (StreamEvents.LibraryFailed) sink.dataOf(StreamEvents.LIBRARY_FAILED).getFirst();
		assertThat(failed.libraryId()).isEqualTo(7);
		assertThat(failed.problem()).containsEntry("code", "library_not_found").containsEntry("status", 404);
		assertThat(sink.batchDone()).extracting(StreamEvents.BatchDone::requested, StreamEvents.BatchDone::failed)
			.containsExactly(1, 1);
	}

	/** ADR-0070: a holding that left the catalog and one whose library is off fail with their own codes. */
	@Test
	void missingHoldingsFailWithTheirOwnCodes() {
		BatchRunner runner = runner(settings(20, 1, 8000, 3000));
		runner.startHoldings(sink, ResultFilter.ALL, 2, List.of(),
				List.of(MissingHolding.holdingNotFound(6), MissingHolding.libraryNotFound(7)), null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);

		assertThat(sink.dataOf(StreamEvents.HOLDING_FAILED)).map(StreamEvents.HoldingFailed.class::cast)
			.extracting(StreamEvents.HoldingFailed::holdingId, failed -> failed.problem().get("code"))
			.containsExactly(tuple(6L, "holding_not_found"), tuple(7L, "library_not_found"));
	}

	@Test
	void sameHostCandidateWaitsWhileOtherHostsStart() { // C2-U-12
		Candidate a1 = new Candidate(shared(1), 1);
		Candidate a2 = new Candidate(shared(2), 1);
		Candidate b = new Candidate(kyobo(3), 1);
		CompletableFuture<LibraryPage> a1Answer = new CompletableFuture<>();
		given(fetcher.fetchAsync(eq(a1.target()), any(), anyInt(), any())).willReturn(a1Answer);
		answer(a2, page(a2, 1, 1, 0));
		answer(b, page(b, 1, 1, 0));

		run(runner(settings(20, 5, 8000, 3000)), ResultFilter.ALL, List.of(a1, a2, b), () -> {
			await().until(() -> sink.count(StreamEvents.LIBRARY_RESULT) == 1);
			// a2 shares a1's host: it is not started while a1 is in flight, b is started instead
			assertThat(requestedIds()).containsExactly(1L, 3L);
			a1Answer.complete(page(a1, 1, 1, 0));
		});

		assertThat(requestedIds()).containsExactly(1L, 3L, 2L);
		assertThat(sink.batchDone().stopReason()).isEqualTo(StreamEvents.StopReason.CANDIDATES_EXHAUSTED);
	}

	@Test
	void sameHostRequestStartsNoEarlierThanTheIntervalAfterThePreviousEnded() { // C2-U-12
		List<Candidate> candidates = List.of(new Candidate(shared(1), 1), new Candidate(shared(2), 1));
		List<Instant> startedAt = new ArrayList<>();
		for (Candidate c : candidates) {
			given(fetcher.fetchAsync(eq(c.target()), any(), anyInt(), any())).willAnswer(call -> {
				synchronized (startedAt) {
					startedAt.add(Instant.now());
				}
				return CompletableFuture.completedFuture(page(c, 1, 1, 0));
			});
		}

		run(runner(settings(20, 5, 8000, 3000)), ResultFilter.ALL, candidates);

		assertThat(Duration.between(startedAt.get(0), startedAt.get(1))).isGreaterThanOrEqualTo(HOST_INTERVAL);
	}

	@Test
	void sameHostCandidatesNotStartedByTheCutoffAreNeitherRequestedNorFailed() { // C2-U-12
		List<Candidate> candidates = List.of(new Candidate(shared(1), 1), new Candidate(shared(2), 1),
				new Candidate(shared(3), 1));
		given(fetcher.fetchAsync(any(), any(), anyInt(), any())).willReturn(new CompletableFuture<>());

		run(runner(settings(20, 5, 600, 300)), ResultFilter.ALL, candidates);

		assertThat(requestedIds()).containsExactly(1L);
		// only the started one fails at the deadline; the others stay "not asked" for the next batch
		assertThat(sink.dataOf(StreamEvents.LIBRARY_FAILED)).extracting(e -> ((StreamEvents.LibraryFailed) e).libraryId())
			.containsExactly(1L);
		assertThat(sink.batchDone().stopReason()).isEqualTo(StreamEvents.StopReason.LAUNCH_CUTOFF);
	}

	// cycle 04 holding batches: the same launch rules on detail pages (ADR-0029)

	@Test
	void holdingsStopAtTheRowLimitAndSubscriptionsSendNoRequest() { // AC-13, 교보 구독형 요청 없음
		List<HoldingTask> tasks = new ArrayList<>();
		for (long id = 1; id <= 3; id++) {
			tasks.add(HoldingTask.of(subscription(100 + id), holding(100 + id, 100 + id, "https://sub" + id + ".test/x")));
		}
		for (long id = 1; id <= 30; id++) {
			SearchTarget target = kyobo(id);
			HoldingTask task = HoldingTask.of(target, holding(id, id, "https://lib" + id + ".test/detail?brcd=" + id));
			answerDetail(task, ParsedDetail.counted(2, 1, 0), Instant.now());
			tasks.add(task);
		}

		runHoldings(runner(settings(20, 5, 8000, 3000)), ResultFilter.ALL, tasks);

		verify(detailPages, never()).fetch(eq(subscription(101)), any(), any());
		assertThat(sink.dataOf(StreamEvents.HOLDING_RESULT)).first()
			.extracting(e -> ((StreamEvents.HoldingResult) e).item().availabilityBasis())
			.isEqualTo(LibraryPage.AvailabilityBasis.SUBSCRIPTION);
		assertThat(sink.count(StreamEvents.HOLDING_REQUESTED)).isLessThan(30).isGreaterThanOrEqualTo(18);
		assertThat(sink.batchDone().stopReason()).isEqualTo(StreamEvents.StopReason.ROW_LIMIT);
		assertThat(sink.names()).endsWith(StreamEvents.BATCH_DONE, StreamEvents.DONE);
	}

	@Test
	void holdingOnAnotherHostFailsWithoutARequest() { // detail_url 검사 [안]
		HoldingTask offHost = HoldingTask.of(kyobo(1), holding(1, 1, "https://elsewhere.test/detail"));
		HoldingTask notHttp = HoldingTask.of(kyobo(2), holding(2, 1, "javascript:alert(1)"));

		runHoldings(runner(settings(20, 5, 8000, 3000)), ResultFilter.ALL, List.of(offHost, notHttp));

		verify(detailPages, never()).fetch(any(), any(), any());
		assertThat(sink.count(StreamEvents.HOLDING_REQUESTED)).isZero();
		assertThat(sink.dataOf(StreamEvents.HOLDING_FAILED)).hasSize(2)
			.allSatisfy(e -> assertThat(((StreamEvents.HoldingFailed) e).problem()).containsEntry("code", "upstream_error"));
	}

	@Test
	void holdingsOnOneHostWaitAndOthersGoFirst() { // 바쁜 호스트 건너뛰기
		HoldingTask a1 = HoldingTask.of(shared(1), holding(1, 1, "https://shared.test:8411/d?1"));
		HoldingTask a2 = HoldingTask.of(shared(1), holding(2, 2, "https://shared.test:8411/d?2"));
		HoldingTask b = HoldingTask.of(kyobo(3), holding(3, 3, "https://lib3.test/d?3"));
		for (HoldingTask task : List.of(a1, a2, b)) {
			answerDetail(task, ParsedDetail.counted(1, 0, 0), Instant.now());
		}

		runHoldings(runner(settings(20, 5, 8000, 3000)), ResultFilter.ALL, List.of(a1, a2, b));

		assertThat(sink.dataOf(StreamEvents.HOLDING_REQUESTED)).extracting(e -> ((StreamEvents.HoldingRequested) e).holdingId())
			.containsExactly(1L, 3L, 2L);
	}

	@Test
	void holdingWithoutAnswerFailsAtTheDeadlineAndCachedPagesAreMarked() { // 8초 마감, cached
		HoldingTask slow = HoldingTask.of(kyobo(1), holding(1, 1, "https://lib1.test/d"));
		HoldingTask cached = HoldingTask.of(kyobo(2), holding(2, 2, "https://lib2.test/d"));
		given(detailPages.fetch(eq(slow.target()), eq(slow.detailUri()), any())).willReturn(new CompletableFuture<>());
		answerDetail(cached, ParsedDetail.counted(3, 3, 1), Instant.now().minusSeconds(60));

		runHoldings(runner(settings(20, 5, 400, 100)), ResultFilter.ALL, List.of(slow, cached));

		assertThat(sink.dataOf(StreamEvents.HOLDING_FAILED)).singleElement()
			.satisfies(e -> assertThat(((StreamEvents.HoldingFailed) e).problem()).containsEntry("code", "upstream_timeout"));
		assertThat(sink.dataOf(StreamEvents.HOLDING_RESULT)).singleElement().satisfies(e -> {
			StreamEvents.HoldingResult result = (StreamEvents.HoldingResult) e;
			assertThat(result.cached()).isTrue();
			assertThat(result.item().availability()).isEqualTo(LibraryPage.Availability.RESERVABLE);
			assertThat(result.item().reservations()).isEqualTo(1);
			assertThat(result.item().titleKey()).isEqualTo("book:2");
		});
	}

	private void runHoldings(BatchRunner runner, ResultFilter filter, List<HoldingTask> tasks) {
		runner.startHoldings(sink, filter, 1, tasks, List.of(), null);
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);
	}

	private void answerDetail(HoldingTask task, ParsedDetail detail, Instant fetchedAt) {
		given(detailPages.fetch(eq(task.target()), eq(task.detailUri()), any()))
			.willReturn(CompletableFuture.completedFuture(new DetailVerdictCache.DetailPage(detail, fetchedAt)));
	}

	private static HoldingRef holding(long holdingId, long bookId, String detailUrl) {
		return new HoldingRef(holdingId, bookId, holdingId, "KYOBO_V1", detailUrl, "책 " + bookId, "저자", "출판사");
	}

	private static SearchTarget subscription(long id) {
		return TestTargets.target(id, PlatformType.KYOBO_SUBSCRIPTION_V1, "https://sub" + (id - 100) + ".test");
	}

	private List<Long> requestedIds() {
		return sink.dataOf(StreamEvents.LIBRARY_REQUESTED)
			.stream()
			.map(e -> ((StreamEvents.LibraryRequested) e).libraryId())
			.toList();
	}

	/** Libraries on one host, different ports (like ebook.gne.go.kr). */
	private static SearchTarget shared(long id) {
		return TestTargets.target(id, PlatformType.KYOBO_V1, "https://shared.test:84" + (10 + id));
	}

	private void run(BatchRunner runner, ResultFilter filter, List<Candidate> candidates) {
		run(runner, filter, candidates, () -> {
		});
	}

	private void run(BatchRunner runner, ResultFilter filter, List<Candidate> candidates, Runnable during) {
		runner.start(sink, QUERY, filter, 2, candidates, List.of(),
				StreamEvents.Targets.of(QUERY, filter.value(), "all", "any", false, List.of()), null);
		during.run();
		await().atMost(Duration.ofSeconds(5)).until(() -> sink.completed);
	}

	private final UsageLog usageLog = mock(UsageLog.class);

	private BatchRunner runner(AvailabilityProperties.Batch settings) {
		return new BatchRunner(mock(LibraryCatalog.class), fetcher, detailPages, new AvailabilityResolvers(detailLookup), settings,
				Duration.ofSeconds(30), HOST_INTERVAL, executor, slots, Clock.systemUTC(), usageLog,
				new com.chaekcheck.availability.internal.ops.LookupOpsCounters());
	}

	private static AvailabilityProperties.Batch settings(int rowLimit, int parallelism, long deadlineMillis,
			long cutoffMillis) {
		return new AvailabilityProperties.Batch(rowLimit, parallelism, Duration.ofMillis(deadlineMillis),
				Duration.ofMillis(cutoffMillis), 50);
	}

	private void answer(Candidate candidate, LibraryPage page) {
		given(fetcher.fetchAsync(eq(candidate.target()), eq(QUERY), eq(candidate.page()), any()))
			.willReturn(CompletableFuture.completedFuture(page));
	}

	private static List<Candidate> kyoboCandidates(int count) {
		return IntStream.rangeClosed(1, count).mapToObj(id -> new Candidate(kyobo(id), 1)).toList();
	}

	private static SearchTarget kyobo(long id) {
		return TestTargets.target(id, PlatformType.KYOBO_V1, "https://lib" + id + ".test");
	}

	private static LibraryPage page(Candidate candidate, int rows, int holdings, int loaned) {
		List<LibraryPage.Item> items = new ArrayList<>();
		for (int i = 1; i <= rows; i++) {
			int rank = (candidate.page() - 1) * 10 + i;
			items.add(new LibraryPage.Item(rank, "t" + rank, null, null, null, holdings, loaned, 0, holdings - loaned,
					"t" + rank, null, null, null, null));
		}
		return new LibraryPage(candidate.target().id(), "사랑", candidate.page(), 10, 100, true, Instant.now(), false, 0,
				null, "https://x", items);
	}

	private static LibraryPage.Item portalRow(int rank, int loaned) {
		return new LibraryPage.Item(rank, "t" + rank, null, null, null, null, loaned, 0, null, "t" + rank, null, null,
				"https://www.gbelib.kr/elib/module/elib/book/view.do?book_idx=" + rank, SiteVerdict.BLANK);
	}

	static final class RecordingSink implements EventSink {

		record Event(String name, Object data) {
		}

		final List<Event> events = new ArrayList<>();

		volatile boolean completed;

		@Override
		public synchronized boolean send(String name, Object data) {
			events.add(new Event(name, data));
			return true;
		}

		@Override
		public void complete() {
			completed = true;
		}

		synchronized List<String> names() {
			return events.stream().map(Event::name).toList();
		}

		synchronized long count(String name) {
			return events.stream().filter(e -> e.name().equals(name)).count();
		}

		synchronized List<Object> dataOf(String name) {
			return events.stream().filter(e -> e.name().equals(name)).map(Event::data).toList();
		}

		StreamEvents.BatchDone batchDone() {
			return (StreamEvents.BatchDone) dataOf(StreamEvents.BATCH_DONE).getFirst();
		}

	}

}
