package com.chaekcheck.catalog.internal.web;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chaekcheck.library.InvalidRegionException;

/**
 * Problem Details for the catalog APIs (ADR-0008), in the same shape as the availability APIs: 400
 * {@code validation_error} with {@code errors[]}, 400 {@code invalid_query}, 404 {@code book_not_found}, 422
 * {@code invalid_region}. Applies to this package only.
 */
@RestControllerAdvice(basePackageClasses = CatalogErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class CatalogErrors {

	@ExceptionHandler
	ProblemDetail invalidParam(InvalidBookParamException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors", List.of(Map.of("field", ex.field, "message", ex.getMessage(), "code", ex.code)));
		return problem;
	}

	@ExceptionHandler
	ProblemDetail invalidQuery(InvalidBookQueryException ex) {
		return problem(HttpStatus.BAD_REQUEST, "invalid_query",
				"Search text must be 2 to 50 characters after trimming spaces");
	}

	@ExceptionHandler
	ProblemDetail bookNotFound(BookNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "book_not_found", "The book does not exist");
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

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}

}
