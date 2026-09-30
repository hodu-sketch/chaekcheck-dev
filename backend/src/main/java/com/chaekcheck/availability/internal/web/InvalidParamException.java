package com.chaekcheck.availability.internal.web;

import java.util.List;

/**
 * Request parameters with bad values: 400 {@code validation_error} with one {@code errors[]} entry per
 * {@link FieldError}, sent before any stream opens.
 */
final class InvalidParamException extends RuntimeException {

	final List<FieldError> errors;

	InvalidParamException(String field, String code, String message) {
		this(List.of(new FieldError(field, code, message)));
	}

	InvalidParamException(List<FieldError> errors) {
		super(errors.getFirst().message());
		this.errors = List.copyOf(errors);
	}

	/** @param code {@code required}, {@code invalid_format}, {@code too_many} or {@code duplicate} */
	record FieldError(String field, String code, String message) {
	}

}
