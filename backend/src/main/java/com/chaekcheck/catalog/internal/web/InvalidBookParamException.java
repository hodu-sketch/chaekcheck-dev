package com.chaekcheck.catalog.internal.web;

/** 400 validation_error for one bad parameter. */
final class InvalidBookParamException extends RuntimeException {

	final String field;

	/** {@code required}, {@code invalid_format}, {@code too_many} or {@code duplicate} */
	final String code;

	InvalidBookParamException(String field, String code, String message) {
		super(message);
		this.field = field;
		this.code = code;
	}

}
