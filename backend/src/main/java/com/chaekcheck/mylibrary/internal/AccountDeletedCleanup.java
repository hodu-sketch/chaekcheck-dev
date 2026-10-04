package com.chaekcheck.mylibrary.internal;

import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * Deletes a deleted account's regions and added libraries (spec R8, ADR-0048). Runs after the deletion commits, in
 * its own transaction, with a publication registry row that stays until it succeeds (ADR-0041, ADR-0051). Running it
 * twice for the same event leaves the same result.
 */
@Component
@ConditionalOnAuthEnabled
class AccountDeletedCleanup {

	private final MyLibraryStore store;

	AccountDeletedCleanup(MyLibraryStore store) {
		this.store = store;
	}

	@ApplicationModuleListener
	void on(AccountDeleted event) {
		store.deleteAccount(event.accountId().value());
	}

}
