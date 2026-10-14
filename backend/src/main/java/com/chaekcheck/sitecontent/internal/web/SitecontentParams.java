package com.chaekcheck.sitecontent.internal.web;

/**
 * Reads the {@code page}, {@code version} and {@code noticeId} values by hand, so a value that is not a number gets
 * this module's 400 {@code validation_error} and never the common 400 {@code bad_request} (C5).
 */
final class SitecontentParams {

	private SitecontentParams() {
	}

	/** A whole number of at least 1, or {@code fallback} when the value is absent. */
	static Integer positive(String field, String value, Integer fallback) {
		if (value == null) {
			return fallback;
		}
		long parsed;
		try {
			parsed = Long.parseLong(value.strip());
		}
		catch (NumberFormatException ex) {
			throw new InvalidParamException(field, "typeMismatch", "must be a whole number");
		}
		if (parsed < 1) {
			throw new InvalidParamException(field, "Min", "must be greater than or equal to 1");
		}
		if (parsed > Integer.MAX_VALUE) {
			throw new InvalidParamException(field, "Max", "must be less than or equal to " + Integer.MAX_VALUE);
		}
		return (int) parsed;
	}

	static long id(String field, String value) {
		long parsed;
		try {
			parsed = Long.parseLong(value.strip());
		}
		catch (NumberFormatException ex) {
			throw new InvalidParamException(field, "typeMismatch", "must be a whole number");
		}
		if (parsed < 1) {
			throw new InvalidParamException(field, "Min", "must be greater than or equal to 1");
		}
		return parsed;
	}

	/** 400 validation_error for one bad value. */
	static final class InvalidParamException extends RuntimeException {

		final String field;

		/** {@code typeMismatch}, {@code Min} or {@code Max} */
		final String code;

		InvalidParamException(String field, String code, String message) {
			super(message);
			this.field = field;
			this.code = code;
		}

	}

}
