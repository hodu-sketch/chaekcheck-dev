package com.chaekcheck.auth;

/**
 * Lets another module store rows keyed by an account without racing the account's deletion (ADR-0051). Exists only
 * when login is on ({@link ConditionalOnAuthEnabled}).
 */
public interface AccountPresence {

	/**
	 * Locks the account row ({@code SELECT … FOR NO KEY UPDATE}) inside the caller's transaction and says whether the
	 * account exists. Call it first in every transaction that writes rows for an account: a deletion that starts
	 * later waits for the commit, and one that committed earlier makes this return {@code false}, so no row of a
	 * deleted account is left behind. Concurrent writes for the same account are serialised by the same lock.
	 * @throws org.springframework.transaction.IllegalTransactionStateException when no transaction is active
	 */
	boolean lockIfExists(AccountId accountId);

}
