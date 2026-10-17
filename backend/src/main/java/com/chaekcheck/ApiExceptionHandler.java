package com.chaekcheck;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.chaekcheck.availability.LookupHealth;

/**
 * Fallback for Spring's own exceptions (unknown path, wrong method, ...) and unexpected errors. Adds the
 * {@code code} member that ADR-0008 requires when a module did not set one. Module advices run first.
 * Not a module: this is the application root package.
 * <p>
 * Cycle 20 (ADR-0118, design review #3): a database failure is answered exactly like any unexpected error (500
 * {@code internal_error}), but is also reported to {@link LookupHealth} so the status signal turns GRAY. Two
 * exception types cover it: {@link DataAccessException} (a query failed, or {@code CannotGetJdbcConnectionException}
 * when no connection could be taken) and {@link CannotCreateTransactionException}, which is a
 * {@code TransactionException}, not a {@code DataAccessException}: the transaction managers throw it when
 * {@code @Transactional} cannot open its connection at the start (Spring Framework javadoc, 2026-10-17).
 */
@RestControllerAdvice
@Order(Ordered.LOWEST_PRECEDENCE)
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

	/** A provider, so web slice tests of one module (no availability beans) still load this advice. */
	private final ObjectProvider<LookupHealth> lookupHealth;

	ApiExceptionHandler(ObjectProvider<LookupHealth> lookupHealth) {
		this.lookupHealth = lookupHealth;
	}

	@ExceptionHandler({ DataAccessException.class, CannotCreateTransactionException.class })
	ResponseEntity<Object> databaseFailure(Exception ex, WebRequest request) {
		lookupHealth.ifAvailable(LookupHealth::recordDbError);
		return unexpected(ex, request);
	}

	@ExceptionHandler
	ResponseEntity<Object> unexpected(Exception ex, WebRequest request) {
		log.error("Unexpected error", ex);
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error");
		return handleExceptionInternal(ex, problem, new HttpHeaders(), HttpStatus.INTERNAL_SERVER_ERROR, request);
	}

	/** Called with the final body, after Spring has built the ProblemDetail of its own exceptions. */
	@Override
	protected ResponseEntity<Object> createResponseEntity(@Nullable Object body, HttpHeaders headers,
			HttpStatusCode statusCode, WebRequest request) {
		if (body instanceof ProblemDetail problem
				&& (problem.getProperties() == null || !problem.getProperties().containsKey("code"))) {
			problem.setProperty("code", codeFor(statusCode));
		}
		return super.createResponseEntity(body, headers, statusCode, request);
	}

	private static String codeFor(HttpStatusCode status) {
		return switch (status.value()) {
			case 400 -> "bad_request";
			case 404 -> "not_found";
			case 405 -> "method_not_allowed";
			case 406 -> "not_acceptable";
			case 415 -> "unsupported_media_type";
			default -> status.is5xxServerError() ? "internal_error" : "request_error";
		};
	}

}
