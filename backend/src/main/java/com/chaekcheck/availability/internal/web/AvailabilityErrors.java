package com.chaekcheck.availability.internal.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chaekcheck.availability.internal.InvalidQueryException;
import com.chaekcheck.availability.internal.Problems;
import com.chaekcheck.availability.internal.UpstreamException;
import com.chaekcheck.availability.internal.search.SearchBusyException;
import com.chaekcheck.library.InvalidRegionException;

/**
 * Maps availability errors to RFC 9457 Problem Details with a {@code code} member (ADR-0008, api.md error table).
 * Details never include upstream bodies, stack traces or SQL.
 */
@RestControllerAdvice(basePackageClasses = AvailabilityErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AvailabilityErrors {

	private static final Logger log = LoggerFactory.getLogger(AvailabilityErrors.class);

	/** Seconds in the 503 {@code Retry-After} header [확정] (cycle 08 handoff step 5-3). */
	static final int BUSY_RETRY_AFTER_SECONDS = 5;

	@ExceptionHandler
	ProblemDetail invalidQuery(InvalidQueryException ex) {
		return problem(HttpStatus.BAD_REQUEST, "invalid_query",
				"Search text must be 2 to 50 characters after trimming spaces");
	}

	@ExceptionHandler
	ProblemDetail invalidPage(InvalidPageException ex) {
		return problem(HttpStatus.BAD_REQUEST, "invalid_page", "page must be an integer of 1 or more");
	}

	@ExceptionHandler
	ProblemDetail libraryNotFound(LibraryNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "library_not_found", "The library does not exist or is disabled");
	}

	@ExceptionHandler
	ProblemDetail invalidRegionIds(RegionIdsParam.InvalidRegionIdsException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors",
				List.of(Map.of("field", ex.field, "message", ex.getMessage(), "code", ex.code)));
		return problem;
	}

	@ExceptionHandler
	ProblemDetail invalidParam(InvalidParamException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors", ex.errors.stream()
			.map(e -> Map.of("field", e.field(), "message", e.message(), "code", e.code()))
			.toList());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail invalidRegion(InvalidRegionException ex) {
		ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_region",
				"Some regions cannot be used");
		problem.setProperty("errors", ex.invalidRegions()
			.stream()
			.map(r -> Map.of("field", r.field(), "message", r.reason().name().toLowerCase(Locale.ROOT), "code",
					r.reason().name().toLowerCase(Locale.ROOT), "value", r.regionId()))
			.toList());
		return problem;
	}

	/**
	 * Every search slot is taken (SearchSlots): refused at once instead of queued, with {@code Retry-After} [확정]
	 * (cycle 08 handoff step 5-3). The batch streams and "더 보기" share this answer; nginx's 429 keeps its own.
	 */
	@ExceptionHandler
	ResponseEntity<ProblemDetail> serverBusy(SearchBusyException ex) {
		log.warn("search refused: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(BUSY_RETRY_AFTER_SECONDS))
			.body(problem(HttpStatus.SERVICE_UNAVAILABLE, "server_busy",
					"Too many searches are running; try again shortly"));
	}

	@ExceptionHandler
	ProblemDetail upstream(UpstreamException ex) {
		log.warn("upstream {}: {}", ex.kind(), ex.getMessage());
		return Problems.upstream(ex);
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		return Problems.problem(status, code, detail);
	}

	static final class InvalidPageException extends RuntimeException {

		InvalidPageException(String page) {
			super("Invalid page: " + page);
		}

	}

	static final class LibraryNotFoundException extends RuntimeException {

		LibraryNotFoundException(String libraryId) {
			super("Library not found: " + libraryId);
		}

	}

}
