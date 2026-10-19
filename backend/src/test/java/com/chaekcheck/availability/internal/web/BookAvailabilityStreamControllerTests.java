package com.chaekcheck.availability.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.EventSink;
import com.chaekcheck.availability.internal.search.HoldingTask;
import com.chaekcheck.availability.internal.search.MissingHolding;
import com.chaekcheck.availability.internal.search.StreamEvents;
import com.chaekcheck.catalog.CatalogHoldings;
import com.chaekcheck.catalog.HoldingRef;
import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.InvalidRegionException;
import com.chaekcheck.library.InvalidRegionException.InvalidRegion;
import com.chaekcheck.library.JoinSurvey;
import com.chaekcheck.library.LibraryType;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.PriorityGroup;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.library.Vendor;

/** Holding streams (cycle 04). Errors come back as Problem Details before any stream opens. */
@WebMvcTest({ BookAvailabilityStreamController.class, BookAvailabilityBatchStreamController.class })
class BookAvailabilityStreamControllerTests {

	static final String ACCEPT = "text/event-stream, application/problem+json";

	static final String URL = "/api/v1/book-availabilities/stream";

	static final String BATCH_URL = "/api/v1/book-availabilities/batches/stream";

	static final SearchTarget SUB = TestTargets.target(3, PlatformType.KYOBO_SUBSCRIPTION_V1, "https://sub.test");

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	BatchRunner batchRunner;

	@MockitoBean
	CatalogHoldings catalogHoldings;

	@BeforeEach
	void setUp() {
		given(batchRunner.streamDuration()).willReturn(Duration.ofSeconds(30));
		given(batchRunner.maxCandidates()).willReturn(3);
		willAnswer(invocation -> {
			EventSink sink = invocation.getArgument(0);
			StreamEvents.HoldingTargets targets = invocation.getArgument(5);
			if (targets != null) {
				sink.send(StreamEvents.TARGETS, targets);
			}
			sink.send(StreamEvents.DONE, new StreamEvents.Done(invocation.getArgument(2)));
			sink.complete();
			return null;
		}).given(batchRunner).startHoldings(any(), any(), anyInt(), anyList(), anyList(), any());
	}

	@Test
	void targetsHoldPlanLibrariesOnlyInBookThenPlanOrder() { // AC-7
		// plan order: GOHEUNG (2), SUB (3), GANGSEO (1); library 99 is not in the plan
		given(batchRunner.plan(eq(Set.of()), eq(Set.of()), any()))
			.willReturn(List.of(TestTargets.GOHEUNG, SUB, TestTargets.GANGSEO));
		given(catalogHoldings.holdingsOf(eq(List.of(20L, 10L)), eq(List.of(2L, 3L, 1L)))).willReturn(List.of(holding(1, 10, 1), holding(2, 10, 2),
				holding(3, 10, 99), holding(4, 20, 3), holding(5, 20, 2)));

		MvcTestResult result = mvc.get().uri(URL + "?bookIds=20,10").header("Accept", ACCEPT).exchange();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<HoldingTask>> tasks = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).startHoldings(any(), any(), eq(1), tasks.capture(), eq(List.of()), any());
		assertThat(tasks.getValue()).extracting(task -> task.holding().holdingId()).containsExactly(5L, 4L, 2L, 1L);
		assertThat(body(result)).contains("\"holdings\":[{\"holdingId\":5,\"bookId\":20,\"libraryId\":2}")
			.contains("\"filter\":\"all\"")
			.doesNotContain("\"libraryId\":99");
	}

	@Test
	void booksWithoutHoldingsGiveAnEmptyBatch() {
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of(TestTargets.GOHEUNG));
		given(catalogHoldings.holdingsOf(eq(List.of(7L)), any())).willReturn(List.of());

		assertThat(body(mvc.get().uri(URL + "?bookIds=7").header("Accept", ACCEPT).exchange()))
			.contains("\"holdings\":[]")
			.contains("event:done");
	}

	@Test
	void scopeAndVisitGoToThePlan() { // AC-12
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of());

		mvc.get().uri(URL + "?bookIds=1&scope=joinable&visit=none&residenceRegionIds=1114").exchange();

		verify(batchRunner).plan(Set.of(1114L), Set.of(),
				new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.NONE));
	}

	@Test
	void badInputIsProblemDetailsBeforeTheStream() {
		assertThat(mvc.get().uri(URL).header("Accept", ACCEPT)).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"bookIds\",\"code\":\"required\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1,x").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"bookIds\",\"code\":\"invalid_format\"}]}");
		String many = String.join(",", java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(Long::toString).toList());
		assertThat(mvc.get().uri(URL + "?bookIds=" + many).header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"bookIds\",\"code\":\"too_many\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1&filter=x").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"filter\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1&visit=x").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"visit\"}]}");
		given(batchRunner.plan(eq(Set.of(9001L)), eq(Set.of()), any())).willThrow(new InvalidRegionException(
				List.of(new InvalidRegion("residenceRegionIds", 9001, InvalidRegionException.Reason.ABOLISHED))));
		assertThat(mvc.get().uri(URL + "?bookIds=1&residenceRegionIds=9001").header("Accept", ACCEPT)).hasStatus(422)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_region");
		verify(batchRunner, never()).startHoldings(any(), any(), anyInt(), anyList(), anyList(), any());
	}

	@Test
	void nextBatchKeepsOrderAndFailsMissingHoldingsOnTheirOwn() {
		given(catalogHoldings.holdings(List.of(5L, 6L, 7L))).willReturn(List.of(holding(5, 1, 2), holding(7, 1, 1)));
		given(batchRunner.target(2)).willReturn(Optional.of(TestTargets.GOHEUNG));
		given(batchRunner.target(1)).willReturn(Optional.empty()); // disabled since

		mvc.get().uri(BATCH_URL + "?batchNo=2&candidates=5,6,7").header("Accept", ACCEPT).exchange();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<HoldingTask>> tasks = ArgumentCaptor.forClass(List.class);
		// 6 is not in the catalog any more, 7's library is disabled (ADR-0070)
		verify(batchRunner).startHoldings(any(), any(), eq(2), tasks.capture(),
				eq(List.of(MissingHolding.holdingNotFound(6), MissingHolding.libraryNotFound(7))), eq(null));
		assertThat(tasks.getValue()).extracting(task -> task.holding().holdingId()).containsExactly(5L);
	}

	@Test
	void nextBatchValidation() {
		assertThat(mvc.get().uri(BATCH_URL + "?candidates=1").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"batchNo\",\"code\":\"required\"}]}");
		assertThat(mvc.get().uri(BATCH_URL + "?batchNo=1&candidates=1").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"batchNo\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri(BATCH_URL + "?batchNo=2&candidates=1,2,3,4").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"candidates\",\"code\":\"too_many\"}]}");
		assertThat(mvc.get().uri(BATCH_URL + "?batchNo=2&candidates=1,1").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"candidates\",\"code\":\"duplicate\"}]}");
	}

	@Test
	void libraryIdsGoToThePlanAndChosenIsInTheTargets() { // cycle 06 AC-6 server side
		SearchTarget chosen = new SearchTarget(12, "lib12", LibraryType.EBOOK, PlatformType.KYOBO_V1,
				"https://chosen.test", PriorityGroup.QUALIFIED, false, Eligibility.UNKNOWN, null, null, false, "join",
				null, null, null, null, true, JoinSurvey.SURVEYED, null, null, false, Vendor.KYOBO, 1, false);
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of(chosen, TestTargets.GOHEUNG));
		given(catalogHoldings.holdingsOf(eq(List.of(10L)), any())).willReturn(List.of(holding(1, 10, 2), holding(2, 10, 12)));

		MvcTestResult result = mvc.get()
			.uri(URL + "?bookIds=10&libraryIds=12&scope=joinable")
			.header("Accept", ACCEPT)
			.exchange();

		verify(batchRunner).plan(Set.of(), Set.of(),
				new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.ANY, Set.of(12L)));
		assertThat(body(result)).contains("\"libraries\":[{\"id\":12,")
			.contains("\"chosen\":true,\"joinSurvey\":\"SURVEYED\",\"logoUrl\":null,\"mobileSiteDiffers\":false,"
					+ "\"vendor\":\"KYOBO\",\"lendingModel\":null,\"orderNo\":1,\"visitOutsideRegion\":false},{\"id\":2,")
			.contains("\"holdings\":[{\"holdingId\":2,\"bookId\":10,\"libraryId\":12}");
	}

	@Test
	void badLibraryIdsAre400AfterVisitAndBeforeRegions() {
		assertThat(mvc.get().uri(URL + "?bookIds=1&libraryIds=0").header("Accept", ACCEPT)).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1&visit=x&libraryIds=0").header("Accept", ACCEPT)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"visit\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1&libraryIds=0&workRegionIds=x").header("Accept", ACCEPT))
			.bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\"}]}");
	}

	@Test
	void holdingLimitKeepsTheFirstPlanLibrariesOnly() { // cycle 10 AC-8 server side
		SearchTarget fourth = TestTargets.target(4, PlatformType.KYOBO_V1, "https://four.test");
		SearchTarget fifth = TestTargets.target(5, PlatformType.KYOBO_V1, "https://five.test");
		// plan order: GOHEUNG (2), SUB (3), GANGSEO (1), 4, 5
		given(batchRunner.plan(any(), any(), any()))
			.willReturn(List.of(TestTargets.GOHEUNG, SUB, TestTargets.GANGSEO, fourth, fifth));
		given(catalogHoldings.holdingsOf(eq(List.of(10L)), any())).willReturn(List.of(holding(1, 10, 1),
				holding(2, 10, 2), holding(4, 10, 4), holding(5, 10, 5), holding(3, 10, 3)));

		MvcTestResult result = mvc.get().uri(URL + "?bookIds=10&holdingLimit=3").header("Accept", ACCEPT).exchange();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<HoldingTask>> tasks = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).startHoldings(any(), any(), eq(1), tasks.capture(), eq(List.of()), any());
		assertThat(tasks.getValue()).extracting(task -> task.holding().libraryId()).containsExactly(2L, 3L, 1L);
		assertThat(body(result)).contains("\"holdings\":[{\"holdingId\":2,\"bookId\":10,\"libraryId\":2},"
				+ "{\"holdingId\":3,\"bookId\":10,\"libraryId\":3},{\"holdingId\":1,\"bookId\":10,\"libraryId\":1}]")
			.doesNotContain("\"libraryId\":4")
			.doesNotContain("\"id\":4,");
	}

	@Test
	void holdingLimitOverSeveralBooksKeepsPlanOrderFirst() {
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of(TestTargets.GOHEUNG, SUB, TestTargets.GANGSEO));
		given(catalogHoldings.holdingsOf(eq(List.of(10L, 20L)), any())).willReturn(List.of(holding(1, 10, 1),
				holding(2, 10, 3), holding(3, 20, 2), holding(4, 20, 1)));

		mvc.get().uri(URL + "?bookIds=10,20&holdingLimit=2").header("Accept", ACCEPT).exchange();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<HoldingTask>> tasks = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).startHoldings(any(), any(), eq(1), tasks.capture(), eq(List.of()), any());
		// plan order 2, 3, 1: holding 3 (library 2) and holding 2 (library 3), in book order
		assertThat(tasks.getValue()).extracting(task -> task.holding().holdingId()).containsExactly(2L, 3L);
	}

	@Test
	void noHoldingLimitKeepsEveryHolding() {
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of(TestTargets.GOHEUNG, SUB, TestTargets.GANGSEO));
		given(catalogHoldings.holdingsOf(eq(List.of(10L)), any()))
			.willReturn(List.of(holding(1, 10, 1), holding(2, 10, 2), holding(3, 10, 3)));

		mvc.get().uri(URL + "?bookIds=10&holdingLimit=").header("Accept", ACCEPT).exchange();

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<HoldingTask>> tasks = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).startHoldings(any(), any(), eq(1), tasks.capture(), eq(List.of()), any());
		assertThat(tasks.getValue()).hasSize(3);
	}

	@Test
	void badHoldingLimitIs400AfterRegions() {
		for (String bad : List.of("0", "21", "x", "3.0", "-1", "007")) {
			assertThat(mvc.get().uri(URL + "?bookIds=1&holdingLimit=" + bad).header("Accept", ACCEPT)).as(bad)
				.hasStatus(400)
				.bodyJson()
				.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"holdingLimit\","
						+ "\"code\":\"invalid_format\"}]}");
		}
		assertThat(mvc.get().uri(URL + "?bookIds=1&holdingLimit=3&holdingLimit=3").header("Accept", ACCEPT))
			.hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"holdingLimit\",\"code\":\"duplicate\"}]}");
		assertThat(mvc.get().uri(URL + "?bookIds=1&holdingLimit=0&workRegionIds=x").header("Accept", ACCEPT))
			.bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"workRegionIds\"}]}");
		verify(batchRunner, never()).startHoldings(any(), any(), anyInt(), anyList(), anyList(), any());
		verify(batchRunner, never()).plan(any(), any(), any());
	}

	private static HoldingRef holding(long id, long bookId, long libraryId) {
		return new HoldingRef(id, bookId, libraryId, "KYOBO_V1", "https://lib.test/d?" + id, "책", "저자", "출판사");
	}

	private static String body(MvcTestResult result) {
		return new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
	}

}
