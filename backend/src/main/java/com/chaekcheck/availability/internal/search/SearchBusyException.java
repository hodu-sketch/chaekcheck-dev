package com.chaekcheck.availability.internal.search;

/** Every search slot is taken ({@link SearchSlots}); maps to 503 {@code server_busy}. */
public class SearchBusyException extends RuntimeException {

	public SearchBusyException(int max) {
		super("all " + max + " search slots are taken");
	}

}
