package com.chaekcheck.auth.internal.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;

/**
 * The one listener of {@link AccountDeleted} in this cycle: a log line with the account number and nothing personal
 * (ADR-0041). It makes the publication registry record and complete a row, which the member module's own listener
 * will do the same way in cycle 06.
 */
@Component
@ConditionalOnAuthEnabled
class AccountDeletedLog {

	private static final Logger log = LoggerFactory.getLogger(AccountDeletedLog.class);

	@ApplicationModuleListener
	void on(AccountDeleted event) {
		log.info("Account {} deleted", event.accountId().value());
	}

}
