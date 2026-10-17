package com.chaekcheck.availability;

/**
 * The one public method of the availability module besides its REST APIs (cycle 20, design review #3, ADR-0118):
 * the root {@code ApiExceptionHandler} reports a DB error here, and the status signal {@code GET /api/v1/lookup-status}
 * answers {@code GRAY} / {@code DB_ERROR} for the next 30 seconds. Nothing else is read through this interface; the
 * time lives inside the module.
 */
public interface LookupHealth {

	/** A request failed on the database ({@code DataAccessException}, {@code CannotCreateTransactionException}). */
	void recordDbError();

}
