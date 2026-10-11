package com.chaekcheck.popular.internal.web;

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
 * Problem Details for the popular APIs (ADR-0008), in the shape of the catalog ones: 400 {@code validation_error}
 * with {@code errors[]}, 404 {@code cover_not_found}, 422 {@code invalid_region}. Applies to this package only; 500
 * stays with the common handler ({@code internal_error}).
 */
@RestControllerAdvice(basePackageClasses = PopularErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class PopularErrors {

	@ExceptionHandler
	ProblemDetail invalidParam(InvalidPopularParamException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors", List.of(Map.of("field", ex.field, "message", ex.getMessage(), "code", ex.code)));
		return problem;
	}

	@ExceptionHandler
	ProblemDetail coverNotFound(CoverNotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, "cover_not_found", "The cover does not exist");
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
