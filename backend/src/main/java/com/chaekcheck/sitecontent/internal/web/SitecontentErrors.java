package com.chaekcheck.sitecontent.internal.web;

import java.util.List;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Problem Details for the notice and document APIs (ADR-0008, plan cycle 16 design-delta "API"): 400
 * {@code validation_error} with {@code errors[]}, 404 {@code notice_not_found}, {@code document_not_found},
 * {@code version_not_found}. Applies to this package only; 500 stays with the common handler ({@code internal_error}).
 */
@RestControllerAdvice(basePackageClasses = SitecontentErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
class SitecontentErrors {

	@ExceptionHandler
	ProblemDetail invalidParam(SitecontentParams.InvalidParamException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Invalid request");
		problem.setProperty("errors", List.of(Map.of("field", ex.field, "message", ex.getMessage(), "code", ex.code)));
		return problem;
	}

	@ExceptionHandler
	ProblemDetail notFound(NotFoundException ex) {
		return problem(HttpStatus.NOT_FOUND, ex.code, ex.getMessage());
	}

	private static ProblemDetail problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setTitle(status.getReasonPhrase());
		problem.setProperty("code", code);
		return problem;
	}

	/** 404 with one of the three codes. */
	static final class NotFoundException extends RuntimeException {

		final String code;

		private NotFoundException(String code, String detail) {
			super(detail);
			this.code = code;
		}

		static NotFoundException notice() {
			return new NotFoundException("notice_not_found", "No such notice");
		}

		static NotFoundException document() {
			return new NotFoundException("document_not_found", "No such document");
		}

		static NotFoundException version() {
			return new NotFoundException("version_not_found", "No such version");
		}

	}

}
