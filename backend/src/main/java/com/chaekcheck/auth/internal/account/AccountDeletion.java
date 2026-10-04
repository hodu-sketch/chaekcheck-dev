package com.chaekcheck.auth.internal.account;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.AccountId;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;

/**
 * The one place an account is deleted: withdrawal, the providers' unlink calls and the daily clean-up jobs all come
 * here (handoff step 7). One transaction deletes the {@code auth.account} row (its login methods, consents and
 * refresh rows go with it by cascade) and publishes {@link AccountDeleted}. The event publication registry records
 * one row per listener in the same transaction, so a listener's work survives a crash (ADR-0041).
 */
@Service
@ConditionalOnAuthEnabled
public class AccountDeletion {

	private final JdbcClient jdbc;

	private final ApplicationEventPublisher events;

	AccountDeletion(JdbcClient jdbc, ApplicationEventPublisher events) {
		this.jdbc = jdbc;
		this.events = events;
	}

	/** Returns {@code false} when the account was already gone (nothing published); idempotent for webhooks. */
	@Transactional
	public boolean delete(long accountId) {
		int deleted = jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(accountId).update();
		if (deleted == 0) {
			return false;
		}
		events.publishEvent(new AccountDeleted(new AccountId(accountId)));
		return true;
	}

}
