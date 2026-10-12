package com.chaekcheck.library;

import java.util.List;

/**
 * Public API of the library module for adding libraries (cycle 13, ADR-0083, ADR-0093). The collection module's
 * library-insert command and the production apply are its callers; the seed changesets and this API are the only
 * ways a library row is made. The caller picks the ids; values are checked by the database's CHECK and UNIQUE
 * constraints, not here.
 */
public interface LibraryRegistry {

	/**
	 * Inserts the libraries with their ids and their eligibility rules, then moves the id sequence past the highest
	 * id, so a later insert without an id does not collide. Runs only inside the caller's transaction.
	 * @throws org.springframework.transaction.IllegalTransactionStateException without a transaction
	 * @throws org.springframework.dao.DataIntegrityViolationException when an id is taken or a value breaks a
	 * constraint (the caller's transaction is then to be rolled back)
	 */
	void add(List<NewLibrary> libraries);

	/**
	 * The id the next added library gets: one past both the highest id and the sequence's last value. Reading it
	 * does not use up a sequence value, so two calls in a row give the same id.
	 */
	long nextId();

	/** Every library, disabled ones included, in id order: what a new library is compared with. */
	List<RegisteredLibrary> listAll();

}
