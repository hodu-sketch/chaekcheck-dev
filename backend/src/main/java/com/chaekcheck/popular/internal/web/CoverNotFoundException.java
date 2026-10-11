package com.chaekcheck.popular.internal.web;

/** 404 cover_not_found: no stored cover with that id (never stored, deleted, or not a number). */
final class CoverNotFoundException extends RuntimeException {

	CoverNotFoundException() {
		super("The cover does not exist");
	}

}
