package com.chaekcheck.collection.internal.libraryinsert;

/**
 * Stops the library-insert command with an exit code (plan cycle 13 handoff step 5): 1 a rules error (no report), 2
 * a same-host suspect, 3 an order number of 2 and up, 4 the report is missing or differs from a new computation.
 */
class LibraryInsertException extends RuntimeException {

	static final int RULES = 1, SAME_HOST = 2, ORDER_NO = 3, REPORT = 4;

	private final int exitCode;

	LibraryInsertException(int exitCode, String message) {
		super(message);
		this.exitCode = exitCode;
	}

	int exitCode() {
		return exitCode;
	}

	static LibraryInsertException rules(String message) {
		return new LibraryInsertException(RULES, message);
	}

}
