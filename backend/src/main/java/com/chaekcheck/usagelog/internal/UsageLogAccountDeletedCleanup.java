package com.chaekcheck.usagelog.internal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * Clears a deleted account's number from the usage rows (ADR-0134): {@code account_id} becomes NULL in
 * {@code book_view}, {@code library_click} and {@code screen_event}; the rows stay for the anonymous statistics. Runs
 * after the deletion commits, in its own transaction, with a publication registry row that stays until it succeeds
 * (ADR-0041, ADR-0051), as {@code mylibrary}'s cleanup does. Running it twice for the same event leaves the same
 * result. Runs whether or not logging is switched on: the rows may come from a time when it was. Named apart from
 * {@code mylibrary}'s listener: Spring bean names are application-wide.
 */
@Component
@ConditionalOnAuthEnabled
class UsageLogAccountDeletedCleanup {

	private static final Logger log = LoggerFactory.getLogger(UsageLogAccountDeletedCleanup.class);

	static final String[] TABLES = { "book_view", "library_click", "screen_event" };

	private final JdbcTemplate jdbc;

	UsageLogAccountDeletedCleanup(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@ApplicationModuleListener
	void on(AccountDeleted event) {
		long accountId = event.accountId().value();
		int cleared = 0;
		for (String table : TABLES) {
			cleared += jdbc.update("UPDATE usagelog." + table + " SET account_id = NULL WHERE account_id = ?", accountId);
		}
		if (cleared > 0) {
			log.info("usage log: account number cleared from {} rows", cleared);
		}
	}

}
