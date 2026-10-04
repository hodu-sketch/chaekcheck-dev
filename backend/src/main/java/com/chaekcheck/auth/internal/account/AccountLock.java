package com.chaekcheck.auth.internal.account;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.AccountPresence;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * The row lock of ADR-0051. {@code FOR NO KEY UPDATE} conflicts with the {@code DELETE} of {@link AccountDeletion} but
 * not with the {@code FOR KEY SHARE} that foreign keys into {@code auth.account} take. The {@code last_login_at}
 * update of a sign-in waits for it (milliseconds).
 */
@Component
@ConditionalOnAuthEnabled
class AccountLock implements AccountPresence {

	private final JdbcClient jdbc;

	AccountLock(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean lockIfExists(AccountId accountId) {
		return jdbc.sql("SELECT 1 FROM auth.account WHERE id = ? FOR NO KEY UPDATE")
			.param(accountId.value())
			.query(Integer.class)
			.optional()
			.isPresent();
	}

}
