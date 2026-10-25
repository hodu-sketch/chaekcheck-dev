package com.chaekcheck.foryou.internal.web;

/** A parameter of the for-you APIs that fails its rule: 400 {@code validation_error}. */
class InvalidForYouParamException extends RuntimeException {

	final String field;

	final String code;

	InvalidForYouParamException(String field, String code, String message) {
		super(message);
		this.field = field;
		this.code = code;
	}

}
