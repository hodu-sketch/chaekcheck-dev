package com.chaekcheck.popular.internal.web;

/** 400 validation_error for one bad parameter. */
final class InvalidPopularParamException extends RuntimeException {

	final String field;

	/** {@code invalid_format}, {@code too_many} or {@code duplicate} */
	final String code;

	InvalidPopularParamException(String field, String code, String message) {
		super(message);
		this.field = field;
		this.code = code;
	}

}
