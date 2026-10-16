package com.chaekcheck.picks.internal.web;

/** 404 list_not_found: the address names no recommended list (ADR-0126). */
final class ListNotFoundException extends RuntimeException {

	ListNotFoundException() {
		super("The list does not exist");
	}

}
