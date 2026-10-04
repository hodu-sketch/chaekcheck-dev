package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.auth.internal.account.AccountDeletion;

/**
 * Cycle 06 step 1 (ADR-0051): the account row lock works only inside the caller's transaction, tells whether the
 * account exists, and makes a deletion wait until the locking transaction ends. No test transaction: the lock must be
 * seen from another connection.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
class AccountPresenceTests {

	/** Long enough that a deletion which did not wait would have finished. */
	private static final Duration HOLD = Duration.ofMillis(700);

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	AccountPresence presence;

	@Autowired
	AccountDeletion deletion;

	@Autowired
	TransactionTemplate transactions;

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
	void needsTheCallersTransaction() {
		account = newAccount();

		assertThatThrownBy(() -> presence.lockIfExists(new AccountId(account)))
			.isInstanceOf(IllegalTransactionStateException.class);
	}

	@Test
	void saysWhetherTheAccountExists() {
		account = newAccount();

		assertThat(lockInNewTransaction(account)).isTrue();
		assertThat(lockInNewTransaction(Long.MAX_VALUE)).isFalse();
	}

	@Test
	void deletionWaitsForTheLockingTransaction() throws Exception {
		account = newAccount();
		CountDownLatch locked = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);

		CompletableFuture<Boolean> saving = CompletableFuture.supplyAsync(() -> transactions.execute(status -> {
			boolean exists = presence.lockIfExists(new AccountId(account));
			locked.countDown();
			await(release);
			return exists;
		}));
		assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

		long start = System.nanoTime();
		CompletableFuture<Boolean> deleting = CompletableFuture.supplyAsync(() -> deletion.delete(account));
		Thread.sleep(HOLD);
		assertThat(deleting).as("deletion must wait for the lock").isNotDone();

		release.countDown();
		assertThat(saving.get(10, TimeUnit.SECONDS)).isTrue();
		assertThat(deleting.get(10, TimeUnit.SECONDS)).isTrue();
		assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(HOLD);
		assertThat(lockInNewTransaction(account)).isFalse();
	}

	private boolean lockInNewTransaction(long accountId) {
		return Boolean.TRUE.equals(transactions.execute(status -> presence.lockIfExists(new AccountId(accountId))));
	}

	private long newAccount() {
		return jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
	}

	private static void await(CountDownLatch latch) {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) {
				throw new IllegalStateException("Not released");
			}
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(ex);
		}
	}

}
