package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.chaekcheck.auth.internal.token.TokenService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;

/**
 * Two refreshes with the same value at the same time never both succeed (C10, {@code FOR UPDATE}). No test
 * transaction here: each refresh must run in its own database transaction. Rows are deleted afterwards.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
class RefreshConcurrencyTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	TokenService tokens;

	@Autowired
	JdbcClient jdbc;

	private final List<Long> accounts = new ArrayList<>();

	@AfterEach
	void deleteAccounts() {
		accounts.forEach(id -> jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(id).update());
	}

	@RepeatedTest(5)
	void sameValueTwiceAtOnceSucceedsAtMostOnce() throws Exception {
		long account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		accounts.add(account);
		String value = tokens.signIn(account).refresh().getValue();

		CyclicBarrier start = new CyclicBarrier(2);
		List<Optional<LoginCookies>> results = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<Optional<LoginCookies>>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return tokens.refresh(value);
				}));
			}
			for (Future<Optional<LoginCookies>> future : futures) {
				results.add(future.get());
			}
		}

		assertThat(results.stream().filter(Optional::isPresent).count()).isLessThanOrEqualTo(1);
	}

}
