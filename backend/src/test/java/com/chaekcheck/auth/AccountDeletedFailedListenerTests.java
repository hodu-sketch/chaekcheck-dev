package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.chaekcheck.auth.internal.account.AccountDeletion;

/**
 * AC-15, failure side: with a test-only listener that always fails, its registry row stays (to be processed again),
 * while the row of the auth module's own listener is removed. So each listener gets its own row.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
		"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
		"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@Import(AccountDeletedFailedListenerTests.FailingListenerConfiguration.class)
class AccountDeletedFailedListenerTests {

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
			jdbc.sql("DELETE FROM modulith.event_publication WHERE serialized_event LIKE ?")
				.param("%\"value\":" + account + "}%")
				.update();
		}
	}

	@Test
	void failedListenerKeepsItsRecordAndTheOtherIsRemoved() {
		account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();

		deletion.delete(account);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
			List<String> listeners = jdbc.sql("""
					SELECT listener_id FROM modulith.event_publication
					WHERE serialized_event LIKE ? AND completion_date IS NULL""")
				.param("%\"value\":" + account + "}%")
				.query(String.class)
				.list();
			assertThat(listeners).singleElement().asString().contains("FailingListener");
		});
		String event = jdbc.sql("SELECT serialized_event FROM modulith.event_publication WHERE serialized_event LIKE ?")
			.param("%\"value\":" + account + "}%")
			.query(String.class)
			.single();
		assertThat(event).isEqualTo("{\"accountId\":{\"value\":" + account + "}}");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class FailingListenerConfiguration {

		@Bean
		FailingListener failingListener() {
			return new FailingListener();
		}

	}

	static class FailingListener {

		@ApplicationModuleListener
		void on(AccountDeleted event) {
			throw new IllegalStateException("test-only listener failure");
		}

	}

}
