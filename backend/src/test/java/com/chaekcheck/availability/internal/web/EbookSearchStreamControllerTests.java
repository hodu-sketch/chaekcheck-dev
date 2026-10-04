package com.chaekcheck.availability.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.Candidate;
import com.chaekcheck.availability.internal.search.EventSink;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.availability.internal.search.StreamEvents;
import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.InvalidRegionException;
import com.chaekcheck.library.InvalidRegionException.InvalidRegion;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * First-batch stream. Requests carry the same Accept header as the web client: errors must come back as Problem
 * Details, not as a stream.
 */
@WebMvcTest(EbookSearchStreamController.class)
class EbookSearchStreamControllerTests {

	static final String ACCEPT = "text/event-stream, application/problem+json";

	static final String URL = "/api/v1/ebook-searches/stream";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	BatchRunner batchRunner;

	@BeforeEach
	void setUp() {
		given(batchRunner.streamDuration()).willReturn(Duration.ofSeconds(30));
		given(batchRunner.maxCandidates()).willReturn(3);
		willAnswer(invocation -> {
			EventSink sink = invocation.getArgument(0);
			StreamEvents.Targets targets = invocation.getArgument(6);
			if (targets != null) {
				sink.send(StreamEvents.TARGETS, targets);
			}
			sink.send(StreamEvents.DONE, new StreamEvents.Done(invocation.getArgument(3)));
			sink.complete();
			return null;
		}).given(batchRunner).start(any(), any(), any(), anyInt(), any(), any(), any());
	}

	@Test
	void firstStreamSendsThePlanAndRunsBatchOneInPlanOrder() { // AC-1
		givenPlan(Set.of(1114L), Set.of(), List.of(TestTargets.GOHEUNG, TestTargets.GANGSEO));

		MvcTestResult result = get(URL + "?title=사랑&residenceRegionIds=1114&filter=available");

		assertThat(result).hasStatusOk().hasContentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM);
		assertThat(result).hasHeader("X-Accel-Buffering", "no");
		// SSE is always UTF-8; MockMvc would default to ISO-8859-1 for text/event-stream without a charset
		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("event:targets\ndata:{\"title\":\"사랑\",\"filter\":\"available\",\"scope\":\"all\","
					+ "\"visit\":\"any\",\"regionFilterApplied\":true,"
					+ "\"libraries\":[{\"id\":2,")
			.contains("\"platformType\":\"KYOBO_V1\",\"priorityGroup\":\"OPEN\"")
			.contains("event:done\ndata:{\"batchNo\":1}");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Candidate>> candidates = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).start(any(), any(), eq(ResultFilter.AVAILABLE), eq(1), candidates.capture(), any(), any());
		assertThat(candidates.getValue()).containsExactly(new Candidate(TestTargets.GOHEUNG, 1),
				new Candidate(TestTargets.GANGSEO, 1));
	}

	@Test
	void missingFilterMeansAll() {
		givenPlan(Set.of(), Set.of(), List.of());

		get(URL + "?title=사랑");

		verify(batchRunner).start(any(), any(), eq(ResultFilter.ALL), eq(1), any(), any(), any());
	}

	@Test
	void noVisitLeavesVisitOnlyLibrariesOutOfThePlan() { // "방문 없이 가입", 2026-09-29
		SearchTarget visit = withVisit(TestTargets.target(3, PlatformType.KYOBO_V1, "https://c.example"));
		givenPlan(Set.of(), Set.of(), List.of(TestTargets.GOHEUNG, visit));

		MvcTestResult result = get(URL + "?title=사랑&visit=none");

		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("\"visit\":\"none\"")
			.doesNotContain("\"id\":3,");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Candidate>> candidates = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).start(any(), any(), any(), eq(1), candidates.capture(), any(), any());
		assertThat(candidates.getValue()).containsExactly(new Candidate(TestTargets.GOHEUNG, 1));
	}

	@Test
	void visitAnyKeepsEveryLibraryAndUnknownVisitIs400() {
		SearchTarget visit = withVisit(TestTargets.target(3, PlatformType.KYOBO_V1, "https://c.example"));
		givenPlan(Set.of(), Set.of(), List.of(TestTargets.GOHEUNG, visit));

		assertThat(new String(get(URL + "?title=사랑&visit=any").getResponse().getContentAsByteArray(),
				StandardCharsets.UTF_8))
			.contains("\"id\":3,");
		assertThat(get(URL + "?title=사랑&visit=some")).bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"visit\"}]}");
	}

	@Test
	void libraryIdsGoToThePlanAndChosenIsInTheTargets() { // cycle 06 AC-6 server side
		SearchTarget chosen = withChosen(TestTargets.target(12, PlatformType.KYOBO_V1, "https://chosen.example"));
		given(batchRunner.plan(eq(Set.of()), eq(Set.of()), any())).willReturn(List.of(chosen, TestTargets.GOHEUNG));

		MvcTestResult result = get(URL + "?title=사랑&libraryIds=12,207,12");

		verify(batchRunner).plan(Set.of(), Set.of(),
				new PlanOptions(PlanOptions.Scope.ALL, PlanOptions.Visit.ANY, Set.of(12L, 207L)));
		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("\"libraries\":[{\"id\":12,")
			.contains("\"homepageUrl\":null,\"chosen\":true},{\"id\":2,")
			.contains("\"chosen\":false}]");
	}

	@Test
	void withoutLibraryIdsThePlanHasNone() { // AC-14
		given(batchRunner.plan(any(), any(), any())).willReturn(List.of());

		get(URL + "?title=사랑&scope=joinable");

		verify(batchRunner).plan(Set.of(), Set.of(), new PlanOptions(PlanOptions.Scope.JOINABLE, PlanOptions.Visit.ANY));
	}

	@Test
	void badLibraryIdsAre400AfterVisitAndBeforeRegions() {
		assertThat(get(URL + "?title=사랑&libraryIds=1,x")).hasStatus(HttpStatus.BAD_REQUEST)
			.bodyJson()
			.isLenientlyEqualTo(
					"{\"code\":\"validation_error\",\"errors\":[{\"field\":\"libraryIds\",\"code\":\"invalid_format\"}]}");
		String many = String.join(",", LongStream.rangeClosed(1, 21).mapToObj(Long::toString).toList());
		assertThat(get(URL + "?title=사랑&libraryIds=" + many)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"too_many\"}]}");
		// 21 ids with a repeat are 20 after merging
		assertThat(get(URL + "?title=사랑&libraryIds=" + many.replace(",21", ",1")).getResponse().getStatus())
			.isNotEqualTo(400);
		assertThat(get(URL + "?title=사랑&visit=x&libraryIds=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"visit\"}]}");
		assertThat(get(URL + "?title=사랑&libraryIds=x&residenceRegionIds=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\"}]}");
	}

	private static SearchTarget withChosen(SearchTarget t) {
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), t.eligibility(), t.eligibilitySummary(), t.extraConditionText(), t.visitRequired(),
				t.joinMethod(), t.joinUrl(), t.joinCaution(), t.joinNotice(), t.homepageUrl(), true);
	}

	/** The real catalog applies scope and visit (PlanOptions); the stub does the same on the given plan. */
	private void givenPlan(Set<Long> residence, Set<Long> work, List<SearchTarget> plan) {
		given(batchRunner.plan(eq(residence), eq(work), any()))
			.willAnswer(invocation -> invocation.<PlanOptions>getArgument(2).apply(plan));
	}

	private static SearchTarget withVisit(SearchTarget t) {
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), t.eligibility(), t.eligibilitySummary(), t.extraConditionText(), true, t.joinMethod(),
				t.joinUrl(), t.joinCaution(), t.joinNotice(), t.homepageUrl(), t.chosen());
	}

	@Test
	void joinableScopeKeepsOnlyLibrariesTheUserCanSurelyJoin() { // C2-U-10
		SearchTarget unknown = withEligibility(TestTargets.target(3, PlatformType.KYOBO_V1, "https://c.example"),
				Eligibility.UNKNOWN, null);
		SearchTarget extra = withEligibility(TestTargets.target(4, PlatformType.KYOBO_V1, "https://d.example"),
				Eligibility.ELIGIBLE, "책이음 회원 가입 가능");
		SearchTarget eligible = withEligibility(TestTargets.target(5, PlatformType.KYOBO_V1, "https://e.example"),
				Eligibility.ELIGIBLE, null);
		// a caution is shown to the user but does not change the eligibility (C2-U-11)
		SearchTarget openWithCaution = withCaution(TestTargets.target(6, PlatformType.KYOBO_V1, "https://f.example"),
				"회원증 발급은 주민만");
		givenPlan(Set.of(), Set.of(), List.of(TestTargets.GOHEUNG, unknown, extra, eligible, openWithCaution));

		MvcTestResult result = get(URL + "?title=사랑&scope=joinable");

		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("\"scope\":\"joinable\"")
			.doesNotContain("\"id\":3,");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Candidate>> candidates = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).start(any(), any(), any(), eq(1), candidates.capture(), any(), any());
		// an extra condition next to a matching region is extra information only (AC-12, 2026-09-29)
		assertThat(candidates.getValue()).containsExactly(new Candidate(TestTargets.GOHEUNG, 1),
				new Candidate(extra, 1), new Candidate(eligible, 1), new Candidate(openWithCaution, 1));
	}

	@Test
	void unknownScopeIs400() {
		assertThat(get(URL + "?title=사랑&scope=some")).bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"scope\"}]}");
	}

	private static SearchTarget withEligibility(SearchTarget t, Eligibility eligibility, String extraConditionText) {
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), eligibility, t.eligibilitySummary(), extraConditionText, t.visitRequired(),
				t.joinMethod(), t.joinUrl(), t.joinCaution(), t.joinNotice(), t.homepageUrl(), t.chosen());
	}

	private static SearchTarget withCaution(SearchTarget t, String joinCaution) {
		return new SearchTarget(t.id(), t.name(), t.libraryType(), t.platformType(), t.baseUrl(), t.priorityGroup(),
				t.deferred(), t.eligibility(), t.eligibilitySummary(), t.extraConditionText(), t.visitRequired(),
				t.joinMethod(), t.joinUrl(), joinCaution, t.joinNotice(), t.homepageUrl(), t.chosen());
	}

	@Test
	void shortQueryIs400BeforeTheStreamOpens() {
		assertProblem(get(URL + "?title=가"), HttpStatus.BAD_REQUEST, "invalid_query");
		verify(batchRunner, org.mockito.Mockito.never()).start(any(), any(), any(), anyInt(), any(), any(), any());
	}

	@Test
	void unknownFilterIs400() {
		assertThat(get(URL + "?title=사랑&filter=some")).bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"filter\"}]}");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "residenceRegionIds=1,x | residenceRegionIds | invalid_format",
			"workRegionIds=-5 | workRegionIds | invalid_format" })
	void malformedRegionIdsAre400(String param, String field, String code) {
		MvcTestResult result = get(URL + "?title=사랑&" + param);

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"%s\",\"code\":\"%s\"}]}".formatted(field, code));
	}

	@Test
	void moreThanTwentyRegionIdsPerFieldIs400() {
		String ids = String.join(",", LongStream.rangeClosed(1101, 1121).mapToObj(Long::toString).toList());

		assertThat(get(URL + "?title=사랑&workRegionIds=" + ids)).bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"code\":\"too_many\"}]}");
	}

	@Test
	void unusableRegionIs422WithFieldAndReason() {
		given(batchRunner.plan(eq(Set.of(9001L)), eq(Set.of()), any())).willThrow(new InvalidRegionException(
				List.of(new InvalidRegion("residenceRegionIds", 9001, InvalidRegionException.Reason.ABOLISHED))));

		MvcTestResult result = get(URL + "?title=사랑&residenceRegionIds=9001");

		assertProblem(result, HttpStatus.UNPROCESSABLE_CONTENT, "invalid_region");
		assertThat(result).bodyJson().isLenientlyEqualTo("""
				{"errors":[{"field":"residenceRegionIds","code":"abolished","value":9001}]}
				""");
	}

	@Test
	void authorConditionSearchesTheAuthorFieldAndNamesItInTargets() { // AC-4, ADR-0025
		givenPlan(Set.of(), Set.of(), List.of(TestTargets.GOHEUNG));

		MvcTestResult result = get(URL + "?author=김달");

		assertThat(result).hasStatusOk();
		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.contains("event:targets\ndata:{\"author\":\"김달\",\"filter\":\"all\",")
			.doesNotContain("\"query\"")
			.doesNotContain("\"title\"")
			.doesNotContain("\"publisher\"");
		verify(batchRunner).start(any(), eq(SearchQuery.of("김달", SearchField.AUTHOR)), any(), eq(1), any(), any(),
				any());
	}

	@Test
	void publisherConditionSearchesThePublisherField() {
		givenPlan(Set.of(), Set.of(), List.of());

		get(URL + "?publisher=빅피시");

		verify(batchRunner).start(any(), eq(SearchQuery.of("빅피시", SearchField.PUBLISHER)), any(), eq(1), any(),
				any(), any());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "'' | title | required", "q=사랑 | title | required",
			"author= | title | required", "author=a&author=b | author | duplicate" })
	void badConditionIs400BeforeTheStreamOpens(String params, String field, String code) { // AC-7
		MvcTestResult result = get(URL + "?" + params);

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"%s\",\"code\":\"%s\"}]}".formatted(field, code));
		verifyNoInteractionsWithStart();
	}

	@Test
	void twoConditionsAre400WithOneRowPerCondition() { // AC-7
		MvcTestResult result = get(URL + "?title=사랑&author=김달");

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson().isLenientlyEqualTo("""
				{"status":400,"title":"Bad Request","detail":"Request validation failed","code":"validation_error",
				 "errors":[{"field":"title","code":"too_many","message":"use only one of title, author, publisher"},
				           {"field":"author","code":"too_many","message":"use only one of title, author, publisher"}]}
				""");
		verifyNoInteractionsWithStart();
	}

	@Test
	void conditionTextOutside2To50IsInvalidQuery() { // AC-7
		assertProblem(get(URL + "?author=가"), HttpStatus.BAD_REQUEST, "invalid_query");
		assertProblem(get(URL + "?publisher=" + "가".repeat(51)), HttpStatus.BAD_REQUEST, "invalid_query");
		verifyNoInteractionsWithStart();
	}

	@Test
	void conditionIsCheckedBeforeFilter() { // validation order [안]: condition, filter, scope, regions
		assertThat(get(URL + "?filter=some")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"title\",\"code\":\"required\"}]}");
	}

	private void verifyNoInteractionsWithStart() {
		verify(batchRunner, org.mockito.Mockito.never()).start(any(), any(), any(), anyInt(), any(), any(), any());
	}

	private MvcTestResult get(String uri) {
		return mvc.get().uri(uri).header("Accept", ACCEPT).exchange(Duration.ofSeconds(5));
	}

	static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo("{\"status\":%d,\"code\":\"%s\"}".formatted(status.value(), code));
	}

}
