package com.chaekcheck.mylibrary.internal.web;

import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.mylibrary.internal.MyLibraryService;

/**
 * Problem Details for the "내 도서관" APIs (ADR-0008), in the shape of the other modules: 400 {@code validation_error}
 * with {@code errors[]}, 401 {@code unauthorized}, 404 {@code library_not_found}, 409 {@code library_limit_reached},
 * 422 {@code invalid_region}. Applies to this package only.
 */
@RestControllerAdvice(basePackageClasses = MyLibraryErrors.class)
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnAuthEnabled
class MyLibraryErrors {

	@ExceptionHandler
	ProblemDetail invalidBody(MyLibraryController.InvalidBodyException ex) {
		ProblemDetail problem = problem(HttpStatus.BAD_REQUEST, "validation_error", "Request validation failed");
		problem.setProperty("errors", ex.errors.stream()
			.map(e -> Map.of("field", e.field(), "message", e.message(), "code", e.code()))
			.toList());
		return problem;
	}

	@ExceptionHandler
	ProblemDetail accountGone(MyLibraryService.AccountGoneException ex) {
		return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Login required");
	}

	@ExceptionHandler({ MyLibraryService.LibraryNotFoundException.class, MyLibraryController.LibraryPathException.class })
	ProblemDetail libraryNotFound(RuntimeException ex) {
		return problem(HttpStatus.NOT_FOUND, "library_not_found", "The library does not exist or is disabled");
	}

	@ExceptionHandler
	ProblemDetail limitReached(MyLibraryService.LibraryLimitException ex) {
		return problem(HttpStatus.CONFLICT, "library_limit_reached",
				"At most " + MyLibraryService.MAX_LIBRARIES + " libraries can be added");
	}

	@ExceptionHandler
	ProblemDetail invalidRegion(MyLibraryService.InvalidRegionsException ex) {
		ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "invalid_region",
				"Some regions cannot be used");
		problem.setProperty("errors", ex.invalidRegions()
			.stream()
			.map(r -> Map.of("field", r.field(), "message", r.code(), "code", r.code(), "value", r.regionId()))
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
