package com.chaekcheck.usagelog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.AccountId;

/**
 * ADR-0134: when an account is deleted, the usage log clears its number from {@code book_view},
 * {@code library_click} and {@code screen_event} and keeps the rows; another member's rows and the visit keys stay,
 * and the same event a second time changes nothing. Login on (the listener exists only then), logging off: the rows
 * are inserted directly, as rows from an earlier time when logging was on. No test transaction: the listener runs
 * after a real commit, so the test empties the tables itself.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.kakao.app-id=123456", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
// its own context (login on, logging off): closed after the class so db-test keeps enough connections
@DirtiesContext
class UsageLogAccountDeletedTests {

	static final long GONE = 777_001L, STAYS = 777_002L;

	static final String KEY = "a1b2c3d4e5f60718a1b2c3d4e5f60718";

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	JdbcClient jdbc;

	@Autowired
	TransactionTemplate transactions;

	@Autowired
	ApplicationEventPublisher events;

	@BeforeEach
	void rows() {
		UsageLogTestTables.empty(jdbc);
		for (long account : List.of(GONE, STAYS)) {
			jdbc.sql("""
					INSERT INTO usagelog.book_view (received_at, book_id, login_status, account_id, visit_key)
					VALUES (now(), 345, 'signed_in', ?, ?), (now(), 346, 'signed_in', ?, NULL)""")
				.params(account, KEY, account)
				.update();
			jdbc.sql("""
					INSERT INTO usagelog.library_click (received_at, library_id, place, screen, login_status, account_id,
					  visit_key)
					VALUES (now(), 12, 'logo', 'picks', 'signed_in', ?, ?)""").params(account, KEY).update();
			jdbc.sql("""
					INSERT INTO usagelog.screen_event (received_at, kind, library_id, login_status, account_id, visit_key)
					VALUES (now(), 'my_library_add', 12, 'signed_in', ?, ?)""").params(account, KEY).update();
		}
		jdbc.sql("INSERT INTO usagelog.book_view (received_at, book_id, login_status) VALUES (now(), 347, 'signed_out')")
			.update();
	}

	@AfterEach
	void empty() {
		UsageLogTestTables.empty(jdbc);
	}

	@Test
	void deletionClearsTheNumberKeepsTheRowsAndCanComeTwice() {
		publish(GONE);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(rowsOf(GONE)).isZero());
		assertThat(registryRows()).isZero();
		assertThat(rowsOf(STAYS)).isEqualTo(4);
		assertThat(UsageLogTestTables.count(jdbc, "book_view")).isEqualTo(5);
		assertThat(UsageLogTestTables.count(jdbc, "library_click")).isEqualTo(2);
		assertThat(UsageLogTestTables.count(jdbc, "screen_event")).isEqualTo(2);
		// the visit keys stay: a visit is not a person
		assertThat(jdbc.sql("SELECT count(*) FROM usagelog.book_view WHERE visit_key = ? AND account_id IS NULL")
			.param(KEY)
			.query(Long.class)
			.single()).isEqualTo(1);

		publish(GONE);

		await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(registryRows()).isZero());
		assertThat(rowsOf(STAYS)).isEqualTo(4);
		assertThat(UsageLogTestTables.count(jdbc, "book_view")).isEqualTo(5);
	}

	private void publish(long account) {
		transactions.executeWithoutResult(status -> events.publishEvent(new AccountDeleted(new AccountId(account))));
	}

	/** Rows of the three tables that still name the account. */
	private long rowsOf(long account) {
		return jdbc.sql("""
				SELECT (SELECT count(*) FROM usagelog.book_view WHERE account_id = :id)
				  + (SELECT count(*) FROM usagelog.library_click WHERE account_id = :id)
				  + (SELECT count(*) FROM usagelog.screen_event WHERE account_id = :id)""")
			.param("id", account)
			.query(Long.class)
			.single();
	}

	private long registryRows() {
		return jdbc.sql("SELECT count(*) FROM modulith.event_publication WHERE serialized_event LIKE ?")
			.param("%\"value\":" + GONE + "}%")
			.query(Long.class)
			.single();
	}

}
