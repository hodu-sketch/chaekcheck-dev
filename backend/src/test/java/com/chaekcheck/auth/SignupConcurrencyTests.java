package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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

import com.chaekcheck.auth.internal.account.AccountStore.NewConsent;
import com.chaekcheck.auth.internal.login.PendingSignup;
import com.chaekcheck.auth.internal.signup.SignupService;
import com.chaekcheck.auth.internal.token.TokenService.LoginCookies;
import com.nimbusds.jwt.SignedJWT;

/**
 * Two sign-ups with the same provider number at the same moment (double click, two tabs) end in one account and
 * both sign in to it (C26). No test transaction: each sign-up runs in its own. Rows are deleted afterwards.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
class SignupConcurrencyTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	SignupService signups;

	@Autowired
	JdbcClient jdbc;

	private final List<String> subjects = new ArrayList<>();

	@AfterEach
	void deleteAccounts() {
		subjects.forEach(subject -> jdbc.sql("""
				DELETE FROM auth.account WHERE id IN (SELECT account_id FROM auth.login_method WHERE subject = ?)""")
			.param(subject)
			.update());
	}

	@RepeatedTest(5)
	void sameProviderNumberTwiceAtOnceGivesOneAccount() throws Exception {
		String subject = "race-" + System.nanoTime();
		subjects.add(subject);
		PendingSignup pending = new PendingSignup("KAKAO", subject, Instant.now(), null);
		List<NewConsent> consents = signups.check(java.util.Map.of(
				SignupService.ConsentType.TERMS_OF_SERVICE, java.util.Optional.of(signups.documents().get(0).version()),
				SignupService.ConsentType.PRIVACY_COLLECTION, java.util.Optional.of(signups.documents().get(1).version()),
				SignupService.ConsentType.AGE_OVER_14, java.util.Optional.empty()));

		CyclicBarrier start = new CyclicBarrier(2);
		List<String> accountIds = new ArrayList<>();
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			List<Future<LoginCookies>> futures = new ArrayList<>();
			for (int i = 0; i < 2; i++) {
				futures.add(pool.submit(() -> {
					start.await();
					return signups.signUp(pending, consents);
				}));
			}
			for (Future<LoginCookies> future : futures) {
				accountIds.add(SignedJWT.parse(future.get().access().getValue()).getJWTClaimsSet().getSubject());
			}
		}

		assertThat(accountIds).hasSize(2).containsOnly(accountIds.getFirst());
		assertThat(jdbc.sql("SELECT count(*) FROM auth.login_method WHERE subject = ?")
			.param(subject)
			.query(Long.class)
			.single()).isEqualTo(1);
	}

}
