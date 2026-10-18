package com.chaekcheck.mylibrary;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.ChaekcheckApplication;
import com.chaekcheck.auth.internal.account.AccountDeletion;

/**
 * Cycle 06 step 6 (ADR-0051, AC-11): a failed {@code AccountDeleted} listener of this module is run again when the
 * application starts again, also when its record is {@code FAILED}. The first run makes the listener fail for real:
 * every connection has {@code lock_timeout} 1s and the test holds a table lock on {@code mylibrary.account_library},
 * so the listener's DELETE times out. Then a new application starts without the lock and must remove the rows.
 * Two applications are started by hand; nothing is left in db-test afterwards.
 */
class AccountDeletedRepublishTests {

	// the usagelog module has its own AccountDeleted listener since ADR-0134 (UsageLogAccountDeletedCleanup)
	private static final String CLEANUP_LISTENER = "mylibrary.internal.AccountDeletedCleanup";

	private final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

	private final String jwtSecret = randomKey();

	private Long account;

	private JdbcClient jdbc;

	@AfterEach
	void cleanUp() {
		if (account != null && jdbc != null) {
			jdbc.sql("DELETE FROM auth.account WHERE id = ?").param(account).update();
			jdbc.sql("DELETE FROM mylibrary.account_region WHERE account_id = ?").param(account).update();
			jdbc.sql("DELETE FROM mylibrary.account_library WHERE account_id = ?").param(account).update();
			jdbc.sql("DELETE FROM modulith.event_publication WHERE serialized_event LIKE ?").param(registryPattern()).update();
		}
		contexts.forEach(ConfigurableApplicationContext::close);
	}

	@Test
	void failedCleanupRunsAgainAfterARestart() throws Exception {
		ConfigurableApplicationContext first = start("spring.datasource.hikari.connection-init-sql=SET lock_timeout = '1s'");
		jdbc = first.getBean(JdbcClient.class);
		account = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO mylibrary.account_region (account_id, kind, region_id, position) VALUES (?, 'RESIDENCE', 1114, 0)")
			.param(account)
			.update();
		jdbc.sql("INSERT INTO mylibrary.account_library (account_id, library_id) VALUES (?, 2)").param(account).update();

		try (Connection lock = first.getBean(DataSource.class).getConnection()) {
			lock.setAutoCommit(false);
			try (Statement statement = lock.createStatement()) {
				statement.execute("LOCK TABLE mylibrary.account_library IN ACCESS EXCLUSIVE MODE");
			}
			first.getBean(AccountDeletion.class).delete(account);

			await().atMost(Duration.ofSeconds(20))
				.untilAsserted(() -> assertThat(statusOf(CLEANUP_LISTENER)).isEqualTo("FAILED"));
			lock.rollback();
		}
		// the listener's transaction was rolled back as a whole: both tables keep their rows
		assertThat(rows("mylibrary.account_region")).isEqualTo(1);
		assertThat(rows("mylibrary.account_library")).isEqualTo(1);
		first.close();

		ConfigurableApplicationContext second = start();
		jdbc = second.getBean(JdbcClient.class);

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			assertThat(rows("mylibrary.account_region")).isZero();
			assertThat(rows("mylibrary.account_library")).isZero();
			assertThat(jdbc.sql("SELECT count(*) FROM modulith.event_publication WHERE serialized_event LIKE ?")
				.param(registryPattern())
				.query(Long.class)
				.single()).isZero();
		});
	}

	private ConfigurableApplicationContext start(String... extra) {
		List<String> properties = new ArrayList<>(List.of("server.port=0", "chaekcheck.auth.enabled=true",
				"chaekcheck.auth.public-base-url=http://localhost:5173",
				"chaekcheck.auth.kakao.admin-key=test-kakao-admin-key", "chaekcheck.auth.kakao.app-id=123456",
				"chaekcheck.auth.naver.client-id=test-naver-id", "chaekcheck.auth.naver.client-secret=test-naver-secret",
				"chaekcheck.auth.kakao.client-id=test-kakao-id", "chaekcheck.auth.kakao.client-secret=test-kakao-secret",
				"chaekcheck.auth.jwt.secret=" + jwtSecret));
		properties.addAll(List.of(extra));
		// command-line arguments: builder properties are only defaults and lose to application-test.yml
		ConfigurableApplicationContext context = new SpringApplicationBuilder(ChaekcheckApplication.class)
			.run(properties.stream().map(property -> "--" + property).toArray(String[]::new));
		contexts.add(context);
		return context;
	}

	private String statusOf(String listener) {
		return jdbc.sql("""
				SELECT status FROM modulith.event_publication
				WHERE serialized_event LIKE ? AND listener_id LIKE ?""")
			.params(registryPattern(), "%" + listener + "%")
			.query(String.class)
			.optional()
			.orElse(null);
	}

	private long rows(String table) {
		return jdbc.sql("SELECT count(*) FROM " + table + " WHERE account_id = ?")
			.param(account)
			.query(Long.class)
			.single();
	}

	private String registryPattern() {
		return "%\"value\":" + account + "}%";
	}

	private static String randomKey() {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		return Base64.getEncoder().encodeToString(key);
	}

}
