package com.chaekcheck.availability.internal.web;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.config.AvailabilityProperties;
import com.chaekcheck.availability.internal.fetch.LibraryPage;
import com.chaekcheck.availability.internal.fetch.LibraryPageFetcher;
import com.chaekcheck.availability.internal.resolve.AvailabilityResolvers;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.SearchTarget;

/**
 * Per-library "more" API: one page of one library (design-delta "페이지 객체"). Rows that a stream would send as
 * PENDING are waited for (up to the detail deadline) and returned with their final values [안].
 */
@RestController
class LibraryEbookController {

	private final LibraryCatalog libraryCatalog;

	private final LibraryPageFetcher fetcher;

	private final AvailabilityResolvers resolvers;

	private final Duration detailDeadline;

	LibraryEbookController(LibraryCatalog libraryCatalog, LibraryPageFetcher fetcher, AvailabilityResolvers resolvers,
			AvailabilityProperties properties) {
		this.libraryCatalog = libraryCatalog;
		this.fetcher = fetcher;
		this.resolvers = resolvers;
		this.detailDeadline = properties.detail().deadline();
	}

	/** Path and query values are read as text so every bad value maps to this API's own error codes. */
	@GetMapping("/api/v1/availability/libraries/{libraryId}/ebooks")
	LibraryPage ebooks(@PathVariable String libraryId, @RequestParam MultiValueMap<String, String> params,
			@RequestParam(required = false) String page) {
		SearchQuery query = SearchConditionParam.parse(params);
		int pageNumber = parsePage(page);
		SearchTarget target = parseLibraryId(libraryId).flatMap(libraryCatalog::findEnabledTarget)
			.orElseThrow(() -> new AvailabilityErrors.LibraryNotFoundException(libraryId));
		LibraryPage fetched = fetcher.fetch(target, query, pageNumber);
		return resolvers.forPlatform(target.platformType())
			.resolve(target, fetched, Instant.now().plus(detailDeadline))
			.completedPage();
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
