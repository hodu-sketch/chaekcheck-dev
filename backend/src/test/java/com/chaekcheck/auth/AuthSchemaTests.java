package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.test.ApplicationModuleTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * The auth and modulith schemas from Liquibase on db-test (step 2). The tables exist whether login is on or off
 * (user decision 2026-10-04); this test runs with login off.
 */
@ApplicationModuleTest
@Transactional
class AuthSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void schemasAndTablesExist() {
		assertThat(jdbc.sql("""
				SELECT table_schema || '.' || table_name FROM information_schema.tables
				WHERE table_schema IN ('auth', 'modulith') ORDER BY 1""").query(String.class).list())
			.containsExactly("auth.account", "auth.account_consent", "auth.login_method", "auth.refresh_token",
					"modulith.event_publication");
	}

	@Test
	void deletingAccountDeletesItsRows() {
		long account = newAccount();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'KAKAO', '1234567890')")
			.param(account)
			.update();
		jdbc.sql("""
				INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
				VALUES (?, 'TERMS_OF_SERVICE', '2026-10-04', now()), (?, 'AGE_OVER_14', NULL, now())""")
			.params(account, account)
			.update();
		jdbc.sql("""
				INSERT INTO auth.refresh_token (account_id, token_hash, expires_at)
				VALUES (?, '\\x01'::bytea, now() + interval '30 days')""").param(account).update();

		jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(account).update();

		for (String table : new String[] { "login_method", "account_consent", "refresh_token" }) {
			assertThat(jdbc.sql("SELECT count(*) FROM auth." + table + " WHERE account_id = ?")
				.param(account)
				.query(Long.class)
				.single()).as(table).isZero();
		}
	}

	@Test
	void methodAndSubjectAreUnique() {
		long first = newAccount();
		long second = newAccount();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'NAVER', 'abc')")
			.param(first)
			.update();
		// The same provider number under another provider is a different person
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'KAKAO', 'abc')")
			.param(second)
			.update();

		assertThatThrownBy(() -> jdbc
			.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'NAVER', 'abc')")
			.param(second)
			.update()).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("login_method_method_subject_key");
	}

	@Test
	void documentVersionIsRequiredExceptForAge() {
		long account = newAccount();

		assertThatThrownBy(() -> jdbc.sql("""
				INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
				VALUES (?, 'PRIVACY_COLLECTION', NULL, now())""").param(account).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("account_consent_document_version_check");
	}

	@Test
	void ageConsentIsRecordedOnce() {
		long account = newAccount();
		String insert = """
				INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
				VALUES (?, 'AGE_OVER_14', NULL, now())""";
		jdbc.sql(insert).param(account).update();

		// NULLS NOT DISTINCT: a second NULL-version row for the same consent is a duplicate
		assertThatThrownBy(() -> jdbc.sql(insert).param(account).update())
			.isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("account_consent_key");
	}

	@Test
	void unknownMethodIsRejected() {
		long account = newAccount();

		assertThatThrownBy(() -> jdbc
			.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'PASSWORD', 'x')")
			.param(account)
			.update()).isInstanceOf(DataIntegrityViolationException.class)
			.hasMessageContaining("login_method_method_check");
	}

	private long newAccount() {
		return jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
	}

}
