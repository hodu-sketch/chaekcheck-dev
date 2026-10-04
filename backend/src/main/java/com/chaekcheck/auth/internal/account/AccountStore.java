package com.chaekcheck.auth.internal.account;

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
