package com.chaekcheck.catalog.internal.web;

/** 400 invalid_query: a field is not 2 to 50 characters after trimming (cycle 01 rule). */
final class InvalidBookQueryException extends RuntimeException {

	InvalidBookQueryException(int length) {
		super("Search text length " + length);
	}

}
