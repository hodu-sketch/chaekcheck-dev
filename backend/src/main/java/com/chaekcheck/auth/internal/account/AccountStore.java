package com.chaekcheck.auth.internal.account;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;

/** Reads and writes {@code auth.account} and its child rows with plain SQL (no JPA entities in this module). */
@Repository
@ConditionalOnAuthEnabled
public class AccountStore {

	private final JdbcClient jdbc;

	AccountStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public boolean exists(long accountId) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.account WHERE id = ?)")
			.param(accountId)
			.query(Boolean.class)
			.single();
	}

	/** The account that signs in with this provider number, if any (R3). */
	public Optional<Long> findByLoginMethod(String method, String subject) {
		return jdbc.sql("SELECT account_id FROM auth.login_method WHERE method = ? AND subject = ?")
			.params(method, subject)
			.query(Long.class)
			.optional();
	}

	/** The provider number of one of the account's login methods (Kakao unlink needs it). */
	public Optional<String> subjectOf(long accountId, String method) {
		return jdbc.sql("SELECT subject FROM auth.login_method WHERE account_id = ? AND method = ? ORDER BY id LIMIT 1")
			.params(accountId, method)
			.query(String.class)
			.optional();
	}

	/** Kakao login methods made before the moment the daily link check started reading Kakao's list (C29). */
	public List<LoginMethodRef> kakaoLoginsCreatedBefore(Instant moment) {
		return jdbc.sql("""
				SELECT account_id, subject FROM auth.login_method
				WHERE method = 'KAKAO' AND created_at < ? ORDER BY account_id""")
			.param(Timestamp.from(moment))
			.query((rs, row) -> new LoginMethodRef(rs.getLong(1), rs.getString(2)))
			.list();
	}

	/** Accounts whose last login is before the cutoff (C30). */
	public List<Long> lastLoginBefore(Instant cutoff) {
		return jdbc.sql("SELECT id FROM auth.account WHERE last_login_at < ? ORDER BY id")
			.param(Timestamp.from(cutoff))
			.query(Long.class)
			.list();
	}

	public record LoginMethodRef(long accountId, String subject) {
	}

	/** Signing in again keeps the account from the one-year inactivity clean-up (C30). */
	public void touchLastLogin(long accountId, Instant now) {
		jdbc.sql("UPDATE auth.account SET last_login_at = ? WHERE id = ?")
			.params(Timestamp.from(now), accountId)
			.update();
	}

	/**
	 * Creates an account with its login method and consents (sign-up, AC-2). Runs in the caller's transaction; a
	 * second sign-up with the same {@code (method, subject)} fails on the UNIQUE constraint
	 * ({@code DuplicateKeyException}).
	 */
	public long create(String method, String subject, List<NewConsent> consents, Instant now) {
		long accountId = jdbc.sql("INSERT INTO auth.account (created_at, last_login_at) VALUES (?, ?) RETURNING id")
			.params(Timestamp.from(now), Timestamp.from(now))
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject, created_at) VALUES (?, ?, ?, ?)")
			.params(accountId, method, subject, Timestamp.from(now))
			.update();
		for (NewConsent consent : consents) {
			jdbc.sql("""
					INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
					VALUES (?, ?, ?, ?)""")
				.params(accountId, consent.type(), consent.version(), Timestamp.from(now))
				.update();
		}
		return accountId;
	}

	/** A consent to record at sign-up; {@code version} is {@code null} for AGE_OVER_14. */
	public record NewConsent(String type, String version) {
	}

	/** The account as {@code GET /api/v1/me} shows it, or empty when it has been deleted. */
	@Transactional(readOnly = true)
	public Optional<AccountView> find(long accountId) {
		if (!exists(accountId)) {
			return Optional.empty();
		}
		List<String> methods = jdbc.sql("SELECT method FROM auth.login_method WHERE account_id = ? ORDER BY id")
			.param(accountId)
			.query(String.class)
			.list();
		List<ConsentView> consents = jdbc.sql("""
				SELECT consent_type, document_version, agreed_at FROM auth.account_consent
				WHERE account_id = ? ORDER BY agreed_at, id""")
			.param(accountId)
			.query((rs, row) -> new ConsentView(rs.getString(1), rs.getString(2),
					rs.getObject(3, OffsetDateTime.class).toInstant()))
			.list();
		return Optional.of(new AccountView(accountId, methods, consents));
	}

	public record AccountView(long id, List<String> loginMethods, List<ConsentView> consents) {
	}

	public record ConsentView(String type, String version, Instant agreedAt) {
	}

}
