package com.chaekcheck.availability.internal.web;

import static com.chaekcheck.availability.internal.web.EbookSearchStreamControllerTests.ACCEPT;
import static com.chaekcheck.availability.internal.web.EbookSearchStreamControllerTests.assertProblem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.HttpStatus;
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
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;

/** Next-batch stream ("더 보기" at the bottom of the list). */
@WebMvcTest(EbookBatchStreamController.class)
class EbookBatchStreamControllerTests {

	static final String BATCH_URL = "/api/v1/ebook-searches/batches/stream";

	@Autowired
	MockMvcTester mvc;

	@MockitoBean
	BatchRunner batchRunner;

	@MockitoBean
	UsageLog usageLog;

	@BeforeEach
	void setUp() {
		given(batchRunner.streamDuration()).willReturn(Duration.ofSeconds(30));
		given(batchRunner.maxCandidates()).willReturn(3);
		willAnswer(invocation -> {
			EventSink sink = invocation.getArgument(0);
			sink.send(StreamEvents.DONE, new StreamEvents.Done(invocation.getArgument(3)));
			sink.complete();
			return null;
		}).given(batchRunner).start(any(), any(), any(), anyInt(), any(), any(), any(), any());
	}

	@Test
	void nextBatchRunsTheCandidatesInOrderAndFailsMissingLibrariesOneByOne() { // cycle 02 AC-5
		given(batchRunner.target(2)).willReturn(Optional.of(TestTargets.GOHEUNG));
		given(batchRunner.target(1)).willReturn(Optional.of(TestTargets.GANGSEO));
		given(batchRunner.target(7)).willReturn(Optional.empty());

		MvcTestResult result = get(BATCH_URL + "?title=사랑&batchNo=2&candidates=2:2,7:1,1:1");

		assertThat(result).hasStatusOk();
		assertThat(new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8))
			.doesNotContain("event:targets")
			.contains("event:done\ndata:{\"batchNo\":2}");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<Candidate>> found = ArgumentCaptor.forClass(List.class);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<BatchRunner.MissingCandidate>> missing = ArgumentCaptor.forClass(List.class);
		verify(batchRunner).start(any(), eq(SearchQuery.of("사랑")), eq(ResultFilter.ALL), eq(2), found.capture(),
				missing.capture(), eq(null), any());
		assertThat(found.getValue()).containsExactly(new Candidate(TestTargets.GOHEUNG, 2),
				new Candidate(TestTargets.GANGSEO, 1));
		assertThat(missing.getValue()).containsExactly(new BatchRunner.MissingCandidate(7, 1));
	}

	@Test
	void authorConditionIsPassedOn() { // AC-4
		given(batchRunner.target(2)).willReturn(Optional.of(TestTargets.GOHEUNG));

		assertThat(get(BATCH_URL + "?author=김달&batchNo=2&candidates=2:2")).hasStatusOk();

		verify(batchRunner).start(any(), eq(SearchQuery.of("김달", SearchField.AUTHOR)), any(), eq(2), any(), any(),
				eq(null), any());
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "batchNo=2&candidates=2:1 | title | required",
			"q=사랑&batchNo=2&candidates=2:1 | title | required",
			"publisher=a&publisher=b&batchNo=2&candidates=2:1 | publisher | duplicate" })
	void badConditionIs400BeforeTheStreamOpens(String params, String field, String code) { // AC-7
		MvcTestResult result = get(BATCH_URL + "?" + params);

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"%s\",\"code\":\"%s\"}]}".formatted(field, code));
		verifyNoInteractionsWithStart();
	}

	@Test
	void twoConditionsAre400() { // AC-7
		MvcTestResult result = get(BATCH_URL + "?title=사랑&publisher=빅피시&batchNo=2&candidates=2:1");

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson().isLenientlyEqualTo("""
				{"errors":[{"field":"title","code":"too_many"},{"field":"publisher","code":"too_many"}]}
				""");
		verifyNoInteractionsWithStart();
	}

	@Test
	void shortConditionTextIsInvalidQuery() { // AC-7
		assertProblem(get(BATCH_URL + "?author=가&batchNo=2&candidates=2:1"), HttpStatus.BAD_REQUEST,
				"invalid_query");
		verifyNoInteractionsWithStart();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "batchNo=2 | candidates | required", "batchNo=2&candidates=1:x | candidates | invalid_format",
			"batchNo=2&candidates=1:1,1:2 | candidates | duplicate",
			"batchNo=2&candidates=1:1,2:1,3:1,4:1 | candidates | too_many", "candidates=1:1 | batchNo | required",
			"batchNo=1&candidates=1:1 | batchNo | invalid_format" })
	void badBatchParametersAre400BeforeTheStreamOpens(String params, String field, String code) { // K = 3 here
		MvcTestResult result = get(BATCH_URL + "?title=사랑&" + params);

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"%s\",\"code\":\"%s\"}]}".formatted(field, code));
		verifyNoInteractionsWithStart();
	}

	private void verifyNoInteractionsWithStart() {
		verify(batchRunner, never()).start(any(), any(), any(), anyInt(), any(), any(), any(), any());
	}

	private MvcTestResult get(String uri) {
		return mvc.get().uri(uri).header("Accept", ACCEPT).exchange(Duration.ofSeconds(5));
	}

	@Test
	void nextBatchIsRecordedAsMoreWithTheExtraValues() { // cycle 15 AC-3 (more)
		given(batchRunner.target(2)).willReturn(Optional.of(TestTargets.GOHEUNG));

		get(BATCH_URL + "?title=채식주의자&filter=available&batchNo=3&candidates=2:2&scope=joinable&visit=none"
				+ "&residenceRegionIds=1114&workRegionIds=x&libraryIds=2,5");

		verify(usageLog).recordMore(any(), eq(UsageLog.MoreKind.DIRECT_NEXT_BATCH), eq(3), eq(null),
				eq(SearchConditions.direct("채식주의자", null, null, "available", "joinable", "none", List.of(1114L),
						List.of(), List.of(2L, 5L))));
	}

	@Test
	void nextBatchWithoutTheExtraValuesStillRuns() {
		given(batchRunner.target(2)).willReturn(Optional.of(TestTargets.GOHEUNG));

		assertThat(get(BATCH_URL + "?title=채식주의자&batchNo=2&candidates=2:1")).hasStatusOk();

		verify(usageLog).recordMore(any(), eq(UsageLog.MoreKind.DIRECT_NEXT_BATCH), eq(2), eq(null),
				eq(SearchConditions.direct("채식주의자", null, null, "all", null, null, List.of(), List.of(),
						List.of())));
	}

}
