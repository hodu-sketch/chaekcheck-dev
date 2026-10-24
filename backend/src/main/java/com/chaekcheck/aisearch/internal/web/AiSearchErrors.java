package com.chaekcheck.aisearch.internal.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chaekcheck.library.InvalidRegionException;

/**
 * The AI search's errors before the stream opens, as RFC 9457 Problem Details with a {@code code} (ADR-0008,
 * design-delta "API"). No detail ever holds the question or the request body.
 */
@RestControllerAdvice(basePackageClasses = AiSearchErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class AiSearchErrors {

	/** Seconds in the 503 {@code server_busy} {@code Retry-After}, as the search slots (cycle 08 [확정]). */
	static final int BUSY_RETRY_AFTER_SECONDS = 5;

	/** Off: the address does not exist (the web then hides the AI tab). */
	static final class OffException extends RuntimeException {

		OffException() {
			super("the AI search is off", null, false, false);
		}

	}

	/** One or more request fields are wrong; the messages name the field only. */
	static final class InvalidRequestException extends RuntimeException {

		final List<Map<String, String>> errors;

		InvalidRequestException(String field, String code, String message) {
			super(field + " " + code, null, false, false);
			this.errors = List.of(Map.of("field", field, "code", code, "message", message));
		}

	}

	static final class InvalidQueryException extends RuntimeException {

		InvalidQueryException() {
			super("question length", null, false, false);
		}

	}

	static final class RateLimitedException extends RuntimeException {

		final long retryAfterSeconds;

		RateLimitedException(long retryAfterSeconds) {
			super("rate limited", null, false, false);
			this.retryAfterSeconds = retryAfterSeconds;
		}

	}

	static final class PausedException extends RuntimeException {

		PausedException() {
			super("daily cost cap", null, false, false);
		}

	}

	static final class BusyException extends RuntimeException {

		BusyException() {
			super("every AI search slot is taken", null, false, false);
		}

	}

	@ExceptionHandler
	ProblemDetail off(OffException ex) {
		return problem(HttpStatus.NOT_FOUND, "not_found", "No such address");
	}

	@ExceptionHandler
	ProblemDetail invalidRequest(InvalidRequestException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors", ex.errors);
		return problem;
	}

	@ExceptionHandler
	ProblemDetail invalidQuery(InvalidQueryException ex) {
		return problem(HttpStatus.BAD_REQUEST, "invalid_query",
				"The question must be 2 to 200 characters after trimming spaces");
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

	@ExceptionHandler
	ResponseEntity<ProblemDetail> rateLimited(RateLimitedException ex) {
		return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds))
			.body(problem(HttpStatus.TOO_MANY_REQUESTS, "too_many_requests", "Too many AI searches; try again later"));
	}

	@ExceptionHandler
	ProblemDetail paused(PausedException ex) {
		return problem(HttpStatus.SERVICE_UNAVAILABLE, "ai_search_paused",
				"The AI search rests for today; the direct search still works");
	}

	@ExceptionHandler
	ResponseEntity<ProblemDetail> busy(BusyException ex) {
		return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
			.header(HttpHeaders.RETRY_AFTER, String.valueOf(BUSY_RETRY_AFTER_SECONDS))
			.body(problem(HttpStatus.SERVICE_UNAVAILABLE, "server_busy",
					"Too many AI searches are running; try again shortly"));
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}

}
