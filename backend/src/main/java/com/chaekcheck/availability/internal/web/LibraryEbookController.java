package com.chaekcheck.availability.internal.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import jakarta.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.ops.LookupOpsCounters;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.availability.internal.search.BatchRunner;
import com.chaekcheck.availability.internal.search.SearchSlots;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageLog.ResponseResult;
import com.chaekcheck.usagelog.UsageLog.ResponseSource;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * Per-library "more" API: one page of one library (design-delta "페이지 객체"). Rows that a stream would send as
 * PENDING are waited for (up to the detail deadline) and returned with their final values [안]. Takes a search slot
 * like a batch stream (503 server_busy when none is free).
 */
@RestController
class LibraryEbookController {

	private static final Logger log = LoggerFactory.getLogger(LibraryEbookController.class);

	private final LibraryCatalog libraryCatalog;

	private final LibraryPageFetcher fetcher;

	private final AvailabilityResolvers resolvers;

	private final Duration detailDeadline;

	private final SearchSlots slots;

	private final UsageLog usageLog;

	private final LookupOpsCounters counters;

	LibraryEbookController(LibraryCatalog libraryCatalog, LibraryPageFetcher fetcher, AvailabilityResolvers resolvers,
			AvailabilityProperties properties, SearchSlots slots, UsageLog usageLog, LookupOpsCounters counters) {
		this.libraryCatalog = libraryCatalog;
		this.fetcher = fetcher;
		this.resolvers = resolvers;
		this.detailDeadline = properties.detail().deadline();
		this.slots = slots;
		this.usageLog = usageLog;
		this.counters = counters;
	}

	/**
	 * Path and query values are read as text so every bad value maps to this API's own error codes. Cycle 15: a valid
	 * request is logged as "more" ({@code filter}, {@code scope}, {@code visit}, regions and {@code libraryIds} are
	 * extra values for that row only), and its request to the library is counted like a direct search's.
	 */
	@GetMapping("/api/v1/availability/libraries/{libraryId}/ebooks")
	LibraryPage ebooks(@PathVariable String libraryId, @RequestParam MultiValueMap<String, String> params,
			@RequestParam(required = false) String page, @RequestParam(required = false) String filter,
			@RequestParam(required = false) String scope, @RequestParam(required = false) String visit,
			@RequestParam(required = false) String residenceRegionIds,
			@RequestParam(required = false) String workRegionIds, @RequestParam(required = false) String libraryIds,
			HttpServletRequest request) {
		UsageRequest usage = usageLog.capture(request);
		SearchQuery query = SearchConditionParam.parse(params);
		int pageNumber = parsePage(page);
		SearchTarget target = parseLibraryId(libraryId).flatMap(libraryCatalog::findEnabledTarget)
			.orElseThrow(() -> new AvailabilityErrors.LibraryNotFoundException(libraryId));
		try (SearchSlots.Slot _ = slots.acquire()) {
			usageLog.recordMore(usage, UsageLog.MoreKind.LIBRARY_MORE, pageNumber, target.id(),
					UsageConditions.more(query, UsageConditions.filterValue(filter), scope, visit, residenceRegionIds,
							workRegionIds, libraryIds));
			LibraryPage fetched = fetch(target, query, pageNumber);
			return resolvers.forPlatform(target.platformType())
				.resolve(target, fetched, Instant.now().plus(detailDeadline))
				.completedPage();
		}
	}

	/**
	 * The library request, counted for the usage log and logged as one line; an answer from the cache counts only as
	 * CACHED, without a line (cycle 19, ADR-0116). Over the body limit is BODY_TOO_LARGE with its own line (cycle 20).
	 */
	private LibraryPage fetch(SearchTarget target, SearchQuery query, int pageNumber) {
		long started = System.nanoTime();
		try {
			LibraryPage fetched = fetcher.fetch(target, query, pageNumber);
			if (fetched.cached()) {
				usageLog.recordLibraryResponse(target.id(), ResponseSource.DIRECT_SEARCH, ResponseResult.CACHED, 0);
			}
			else {
				answered(target.id(), ResponseResult.OK, started, null);
			}
			return fetched;
		}
		catch (UpstreamException ex) {
			if (ex.bodyTooLarge()) {
				BatchRunner.bodyTooLarge(target.id(), ex, Instant.now(), counters);
			}
			answered(target.id(), BatchRunner.resultOf(ex), started, ex.getMessage());
			throw ex;
		}
	}

	private void answered(long libraryId, ResponseResult result, long startedNanos, String error) {
		long ms = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
		counters.libraryRequest();
		usageLog.recordLibraryResponse(libraryId, ResponseSource.DIRECT_SEARCH, result, ms);
		String name = result.name().toLowerCase(java.util.Locale.ROOT);
		if (error == null) {
			log.info("library={} source=direct_search result={} ms={}", libraryId, name, ms);
		}
		else {
			log.info("library={} source=direct_search result={} ms={} error={}", libraryId, name, ms, error);
		}
	}

	/** Missing page means 1. */
	private static int parsePage(String page) {
		if (page == null) {
			return 1;
		}
		try {
			int value = Integer.parseInt(page.strip());
			if (value >= 1) {
				return value;
			}
		}
		catch (NumberFormatException ignored) {
			// falls through to invalid_page
		}
		throw new AvailabilityErrors.InvalidPageException(page);
	}

	private static Optional<Long> parseLibraryId(String libraryId) {
		try {
			return Optional.of(Long.parseLong(libraryId));
		}
		catch (NumberFormatException ex) {
			return Optional.empty();
		}
	}

}
