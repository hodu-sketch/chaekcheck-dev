package com.chaekcheck.availability.internal.web;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;

import com.chaekcheck.availability.internal.SearchField;
import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.adapter.SiteVerdict;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolver;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.resolve.DetailLookupResolver;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlatformType;
import com.chaekcheck.availability.internal.TestTargets;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;

@WebMvcTest(LibraryEbookController.class)
@Import(LibraryEbookControllerTests.Resolvers.class)
class LibraryEbookControllerTests {

	/** Real resolvers; only the detail lookup is a mock. */
	@TestConfiguration
	static class Resolvers {

		@Bean
		AvailabilityResolvers availabilityResolvers(DetailLookupResolver detailLookup) {
			return new AvailabilityResolvers(detailLookup);
		}

		@Bean
		SearchSlots searchSlots() {
			return new SearchSlots(1);
		}

		@Bean
		AvailabilityProperties availabilityProperties() {
			return AdapterFixtures.PROPERTIES;
		}

	}

	static final SearchTarget GOHEUNG = TestTargets.GOHEUNG;

	static final String URL = "/api/v1/availability/libraries/{id}/ebooks";

	@Autowired
	MockMvcTester mvc;

	@Autowired
	SearchSlots searchSlots;

	@MockitoBean
	UsageLog usageLog;

	@MockitoBean
	LibraryCatalog libraryCatalog;

	@MockitoBean
	LibraryPageFetcher fetcher;

	@MockitoBean
	DetailLookupResolver detailLookup;

	@Test
	void returnsPageObjectWithoutDescriptionOrCover() { // AC-11, AC-15
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 2)).willReturn(new LibraryPage(2, "사랑", 2, 50, 103, true,
				Instant.parse("2026-09-27T05:12:00Z"), true, 140, 0.41, "https://www.ghlib.go.kr:446/x",
				List.of(new LibraryPage.Item(51, "제목", "저자", "출판사", "교보문고", 10, 0, 0, 10, "제목", null, null,
						"https://www.ghlib.go.kr:446/elibrary-front/content/contentView.ink?brcd=1", null))));

		MvcTestResult result = mvc.get().uri(URL + "?title= 사랑 &page=2", 2).exchange();

		assertThat(result).hasStatusOk().bodyJson().isStrictlyEqualTo("""
				{"libraryId":2,"query":"사랑","page":2,"pageSize":50,"totalCount":103,"hasMore":true,
				 "fetchedAt":"2026-09-27T05:12:00Z","cached":true,"cacheAgeSeconds":140,"loanRate":0.41,
				 "librarySearchUrl":"https://www.ghlib.go.kr:446/x",
				 "items":[{"rank":51,"title":"제목","author":"저자","publisher":"출판사","supplier":"교보문고",
				           "holdings":10,"loaned":0,"reservations":0,"available":10,"titleKey":"제목",
				           "availability":"AVAILABLE","availabilityBasis":"COUNT",
				           "detailUrl":"https://www.ghlib.go.kr:446/elibrary-front/content/contentView.ink?brcd=1"}]}
				""");
	}

	@Test
	void portalRowsPendingInAStreamAreAnsweredWithTheirDetailVerdict() { // AC-15, [안] wait before answering
		SearchTarget portal = TestTargets.target(12, PlatformType.ELIB_PORTAL_V1, "https://www.gbelib.kr/elib");
		given(libraryCatalog.findEnabledTarget(12)).willReturn(Optional.of(portal));
		LibraryPage.Item onLoan = new LibraryPage.Item(4, "신 퇴마록 신세편 1", null, null, null, null, 5, 1, null, "신퇴마록신세편1",
				null, null, "https://www.gbelib.kr/elib/module/elib/book/view.do?menu_idx=10&book_idx=63941&type=EBK",
				SiteVerdict.BLANK);
		given(fetcher.fetch(portal, SearchQuery.of("퇴마록"), 1)).willReturn(new LibraryPage(12, "퇴마록", 1, 10, 1, false,
				Instant.parse("2026-09-28T05:00:00Z"), false, 0, null, "https://www.gbelib.kr/elib/x", List.of(onLoan)));
		given(detailLookup.lookup(any(), any(), any())).willReturn(CompletableFuture.completedFuture(
				new AvailabilityResolver.Decision(LibraryPage.Availability.RESERVABLE,
						LibraryPage.AvailabilityBasis.SITE_DETAIL)));

		MvcTestResult result = mvc.get().uri(URL + "?title=퇴마록", 12).exchange();

		assertThat(result).hasStatusOk().bodyJson().isLenientlyEqualTo("""
				{"loanRate":null,"items":[{"rank":4,"holdings":null,"available":null,"loaned":5,
				  "availability":"RESERVABLE","availabilityBasis":"SITE_DETAIL"}]}
				""");
		assertThat(result).bodyText().doesNotContain("siteVerdict");
	}

	@Test
	void missingPageMeansFirstPage() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(any(), any(), eq(1))).willReturn(new LibraryPage(2, "사랑", 1, 50, 0, false, Instant.now(),
				false, 0, null, "https://x", List.of()));

		assertThat(mvc.get().uri(URL + "?title=사랑", 2)).hasStatusOk();
	}

	@ParameterizedTest
	@ValueSource(strings = { "?title=가", "?title=  가  ", "?author=가" })
	void shortQueryIsInvalidQuery(String query) {
		assertProblem(mvc.get().uri(URL + query, 2).exchange(), HttpStatus.BAD_REQUEST, "invalid_query");
		verifyNoInteractions(fetcher);
	}

	@Test
	void publisherConditionIsPassedOn() { // AC-4
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(GOHEUNG, SearchQuery.of("빅피시", SearchField.PUBLISHER), 2)).willReturn(new LibraryPage(2,
				"빅피시", 2, 10, 30, true, Instant.now(), false, 0, null, "https://x", List.of()));

		assertThat(mvc.get().uri(URL + "?publisher=빅피시&page=2", 2)).hasStatusOk()
			.bodyJson()
			.isLenientlyEqualTo("{\"query\":\"빅피시\",\"page\":2}");
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "'' | title | required", "?q=사랑 | title | required",
			"?publisher= | title | required", "?author=a&author=b | author | duplicate" })
	void badConditionIsValidationError(String query, String field, String code) { // AC-7
		MvcTestResult result = mvc.get().uri(URL + query, 2).exchange();

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"%s\",\"code\":\"%s\"}]}".formatted(field, code));
		verifyNoInteractions(fetcher);
	}

	@Test
	void twoConditionsAreValidationError() { // AC-7
		MvcTestResult result = mvc.get().uri(URL + "?author=김달&publisher=빅피시", 2).exchange();

		assertProblem(result, HttpStatus.BAD_REQUEST, "validation_error");
		assertThat(result).bodyJson().isLenientlyEqualTo("""
				{"errors":[{"field":"author","code":"too_many"},{"field":"publisher","code":"too_many"}]}
				""");
		verifyNoInteractions(fetcher);
	}

	@Test
	void longQueryIsInvalidQuery() {
		assertProblem(mvc.get().uri(URL + "?title=" + "가".repeat(51), 2).exchange(), HttpStatus.BAD_REQUEST,
				"invalid_query");
	}

	@ParameterizedTest
	@ValueSource(strings = { "0", "-1", "abc", "1.5" })
	void badPageIsInvalidPage(String page) {
		assertProblem(mvc.get().uri(URL + "?title=사랑&page=" + page, 2).exchange(), HttpStatus.BAD_REQUEST,
				"invalid_page");
		verifyNoInteractions(fetcher);
	}

	@ParameterizedTest
	@ValueSource(strings = { "999", "abc" })
	void unknownOrDisabledLibraryIsLibraryNotFound(String id) {
		given(libraryCatalog.findEnabledTarget(anyLong())).willReturn(Optional.empty());

		assertProblem(mvc.get().uri(URL + "?title=사랑", id).exchange(), HttpStatus.NOT_FOUND, "library_not_found");
	}

	@Test
	void upstreamErrorIs502() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(any(), any(), anyInt())).willThrow(UpstreamException.error("status 500 <html>secret</html>"));

		MvcTestResult result = mvc.get().uri(URL + "?title=사랑", 2).exchange();

		assertProblem(result, HttpStatus.BAD_GATEWAY, "upstream_error");
		assertThat(result).bodyText().doesNotContain("secret");
	}

	@Test
	void everySearchSlotTakenIs503WithoutCallingTheLibrary() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));

		try (SearchSlots.Slot _ = searchSlots.acquire()) { // the only slot (test configuration)
			MvcTestResult result = mvc.get().uri(URL + "?title=사랑", 2).exchange();
			assertProblem(result, HttpStatus.SERVICE_UNAVAILABLE, "server_busy");
			assertThat(result).headers().hasValue("Retry-After", "5"); // AC-18
		}
		verifyNoInteractions(fetcher);
		assertThat(searchSlots.available()).isEqualTo(1);
	}

	@Test
	void slotIsGivenBackAfterAnUpstreamError() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(any(), any(), anyInt())).willThrow(UpstreamException.error("status 500"));

		mvc.get().uri(URL + "?title=사랑", 2).exchange();

		assertThat(searchSlots.available()).isEqualTo(1);
	}

	@Test
	void upstreamTimeoutIs504() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(any(), any(), anyInt())).willThrow(UpstreamException.timeout("slow"));

		assertProblem(mvc.get().uri(URL + "?title=사랑", 2).exchange(), HttpStatus.GATEWAY_TIMEOUT, "upstream_timeout");
	}

	@Test
	void unknownPathGetsCodeFromRootHandler() {
		assertProblem(mvc.get().uri("/api/v1/nothing-here").exchange(), HttpStatus.NOT_FOUND, "not_found");
	}

	private static void assertProblem(MvcTestResult result, HttpStatus status, String code) {
		assertThat(result).hasStatus(status)
			.hasContentType(MediaType.APPLICATION_PROBLEM_JSON)
			.bodyJson()
			.isLenientlyEqualTo("""
					{"status":%d,"title":"%s","code":"%s"}
					""".formatted(status.value(), status.getReasonPhrase(), code));
	}

	@Test
	void libraryMoreIsRecordedAndItsRequestCounted() { // cycle 15 AC-3 (more), AC-24
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(GOHEUNG, SearchQuery.of("채식주의자"), 2)).willReturn(new LibraryPage(2, "채식주의자", 2, 50,
				0, false, Instant.now(), false, 0, null, "https://www.ghlib.go.kr:446/x", List.of()));

		assertThat(mvc.get().uri(URL + "?title=채식주의자&page=2&filter=available&visit=none", 2).exchange())
			.hasStatusOk();

		verify(usageLog).recordMore(any(), eq(UsageLog.MoreKind.LIBRARY_MORE), eq(2), eq(2L),
				eq(SearchConditions.direct("채식주의자", null, null, "available", null, "none", List.of(), List.of(),
						List.of())));
		verify(usageLog).recordLibraryResponse(eq(2L), eq(UsageLog.ResponseSource.DIRECT_SEARCH),
				eq(UsageLog.ResponseResult.OK), anyLong());
	}

	@Test
	void cachedPageIsNotALibraryRequest() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 1)).willReturn(new LibraryPage(2, "사랑", 1, 50, 0, false,
				Instant.now(), true, 30, null, "https://www.ghlib.go.kr:446/x", List.of()));

		assertThat(mvc.get().uri(URL + "?title=사랑", 2).exchange()).hasStatusOk();

		verify(usageLog, never()).recordLibraryResponse(anyLong(), any(), any(), anyLong());
	}

	@Test
	void failedRequestIsCountedByKind() {
		given(libraryCatalog.findEnabledTarget(2)).willReturn(Optional.of(GOHEUNG));
		given(fetcher.fetch(GOHEUNG, SearchQuery.of("사랑"), 1))
			.willThrow(com.chaekcheck.availability.internal.UpstreamException.timeout("read timed out"));

		mvc.get().uri(URL + "?title=사랑", 2).exchange();

		verify(usageLog).recordLibraryResponse(eq(2L), eq(UsageLog.ResponseSource.DIRECT_SEARCH),
				eq(UsageLog.ResponseResult.TIMEOUT), anyLong());
	}

}
