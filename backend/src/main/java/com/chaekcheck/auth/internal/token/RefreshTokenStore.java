package com.chaekcheck.auth.internal.token;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.ConditionalOnAuthEnabled;

/**
 * {@code auth.refresh_token}: one row per browser login. Only SHA-256 hashes of the values are stored (C9).
 */
@Repository
@ConditionalOnAuthEnabled
class RefreshTokenStore {

	private final JdbcClient jdbc;

	RefreshTokenStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	void insert(long accountId, byte[] tokenHash, Instant expiresAt) {
		jdbc.sql("INSERT INTO auth.refresh_token (account_id, token_hash, expires_at) VALUES (?, ?, ?)")
			.params(accountId, tokenHash, Timestamp.from(expiresAt))
			.update();
	}

	/**
	 * Replaces the presented value by a new one (C10). The row is found by the current or the previous value and
	 * locked, so two requests with the same value never both succeed: the second one waits, then finds the value
	 * as "previous" and ends that login.
	 * <ul>
	 * <li>current value, not expired: rotated; {@code expires_at} stays (a login lasts 30 days from sign-in)</li>
	 * <li>previous value: reused, the row is deleted</li>
	 * <li>unknown or expired: nothing is deleted (expired rows go in the daily clean-up)</li>
	 * </ul>
	 */
	@Transactional
	Rotation rotate(byte[] presentedHash, byte[] newHash, Instant now) {
		List<Row> rows = jdbc.sql("""
				SELECT id, account_id, token_hash, expires_at FROM auth.refresh_token
				WHERE token_hash = ? OR previous_token_hash = ?
				ORDER BY id
				FOR UPDATE""")
			.params(presentedHash, presentedHash)
			.query((rs, rowNum) -> new Row(rs.getLong(1), rs.getLong(2), rs.getBytes(3),
					rs.getObject(4, OffsetDateTime.class).toInstant()))
			.list();
		if (rows.isEmpty()) {
			return new Rotation.Rejected();
		}
		Row row = rows.getFirst();
		if (!Arrays.equals(row.tokenHash(), presentedHash)) {
			jdbc.sql("DELETE FROM auth.refresh_token WHERE id = ?").param(row.id()).update();
			return new Rotation.Reused(row.accountId());
		}
		if (!row.expiresAt().isAfter(now)) {
			return new Rotation.Rejected();
		}
		jdbc.sql("""
				UPDATE auth.refresh_token SET previous_token_hash = token_hash, token_hash = ?, rotated_at = ?
				WHERE id = ?""").params(newHash, Timestamp.from(now), row.id()).update();
		return new Rotation.Rotated(row.accountId(), row.expiresAt());
	}

	/** Ends the login of one browser: the row that holds the value as current or previous (logout is idempotent). */
	void deleteByValue(byte[] tokenHash) {
		jdbc.sql("DELETE FROM auth.refresh_token WHERE token_hash = ? OR previous_token_hash = ?")
			.params(tokenHash, tokenHash)
			.update();
	}

	/** The account of a refresh value that is current and not expired (not the previous one). */
	Optional<Long> accountOf(byte[] tokenHash, Instant now) {
		return jdbc.sql("SELECT account_id FROM auth.refresh_token WHERE token_hash = ? AND expires_at > ?")
			.params(tokenHash, Timestamp.from(now))
			.query(Long.class)
			.optional();
	}

	int deleteExpired(Instant now) {
		return jdbc.sql("DELETE FROM auth.refresh_token WHERE expires_at < ?").param(Timestamp.from(now)).update();
	}

	private record Row(long id, long accountId, byte[] tokenHash, Instant expiresAt) {
	}

	sealed interface Rotation {

		record Rotated(long accountId, Instant expiresAt) implements Rotation {
		}

		record Reused(long accountId) implements Rotation {
		}

		record Rejected() implements Rotation {
		}

	}

}
