package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.lang.reflect.RecordComponent;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.chaekcheck.auth.internal.account.AccountDeletion;

/**
 * Step 7 (AC-15): deleting an account removes its rows and publishes {@link AccountDeleted}; the publication
 * registry row of the listener is gone once the listener has finished ({@code completion-mode: delete}). No test
 * transaction: listeners run after the commit.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
class AccountDeletionTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	AccountDeletion deletion;

	@Autowired
	JdbcClient jdbc;

	private Long account;

	@AfterEach
	void cleanUp() {
		if (account != null) {
			jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(account).update();
			jdbc.sql("DELETE FROM modulith.event_publication WHERE serialized_event LIKE ?")
				.param("%\"value\":" + account + "}%")
				.update();
		}
	}

	@Test
	void deletingRemovesTheRowsAndTheRegistryRowAfterTheListener() {
		account = newAccountWithRows();

		assertThat(deletion.delete(account)).isTrue();

		for (String table : List.of("auth.account WHERE id", "auth.login_method WHERE account_id",
				"auth.account_consent WHERE account_id", "auth.refresh_token WHERE account_id")) {
			assertThat(count(table + " = " + account)).as(table).isZero();
		}
		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(registryRows(account)).isZero());
	}

	@Test
	void deletingAnAccountThatIsGonePublishesNothing() {
		assertThat(deletion.delete(Long.MAX_VALUE)).isFalse();
	}

	@Test
	void theEventCarriesOnlyTheAccountNumber() {
		assertThat(Arrays.stream(AccountDeleted.class.getRecordComponents()).map(RecordComponent::getName))
			.containsExactly("accountId");
		assertThat(Arrays.stream(AccountId.class.getRecordComponents()).map(RecordComponent::getName))
			.containsExactly("value");
	}

	private long newAccountWithRows() {
		long id = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, 'KAKAO', ?)")
			.params(id, "del-" + id)
			.update();
		jdbc.sql("""
				INSERT INTO auth.account_consent (account_id, consent_type, document_version, agreed_at)
				VALUES (?, 'TERMS_OF_SERVICE', '2026-10-04', now())""").param(id).update();
		jdbc.sql("""
				INSERT INTO auth.refresh_token (account_id, token_hash, expires_at)
				VALUES (?, ?, now() + interval '30 days')""").params(id, ("h" + id).getBytes()).update();
		return id;
	}

	private long count(String fromWhere) {
		return jdbc.sql("SELECT count(*) FROM " + fromWhere).query(Long.class).single();
	}

	long registryRows(long accountId) {
		return jdbc.sql("SELECT count(*) FROM modulith.event_publication WHERE serialized_event LIKE ?")
			.param("%\"value\":" + accountId + "}%")
			.query(Long.class)
			.single();
	}

}
