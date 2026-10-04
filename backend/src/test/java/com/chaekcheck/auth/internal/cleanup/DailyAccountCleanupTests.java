package com.chaekcheck.auth.internal.cleanup;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.auth.AccountDeleted;
import com.chaekcheck.auth.FakeProvider;
import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.account.AccountDeletion;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.login.KakaoLinkedUsers;
import com.chaekcheck.auth.internal.login.ProviderUnlinker;

/**
 * Step 10 (AC-19, AC-20): the Kakao link check and the one-year clean-up, with a fake Kakao user list and a fixed
 * clock. Kakao's call spacing is 0 here.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.kakao.app-id=123456", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret", "chaekcheck.auth.cleanup.kakao-call-interval=0s" })
@Transactional
@RecordApplicationEvents
class DailyAccountCleanupTests {

	static final FakeProvider fake = FakeProvider.start();

	static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.SECONDS);

	@DynamicPropertySource
	static void settings(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
		registry.add("chaekcheck.auth.kakao.user-ids-uri", () -> fake.url("/kakao/ids"));
		registry.add("chaekcheck.auth.kakao.unlink-uri", () -> fake.url("/kakao/unlink"));
	}

	@AfterAll
	static void stopFake() {
		fake.server.stop(0);
	}

	@Autowired
	AuthProperties properties;

	@Autowired
	AccountStore accounts;

	@Autowired
	AccountDeletion deletion;

	@Autowired
	KakaoLinkedUsers kakaoUsers;

	@Autowired
	ProviderUnlinker unlinker;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ApplicationEvents events;

	DailyAccountCleanup cleanup;

	@BeforeEach
	void setUp() {
		fake.reset();
		cleanup = new DailyAccountCleanup(properties, accounts, deletion, kakaoUsers, unlinker,
				Clock.fixed(NOW, ZoneOffset.UTC));
	}

	@Test
	void linkCheckDeletesOnlyAccountsKakaoNoLongerLists() {
		long a = account("KAKAO", "1001", NOW.minus(Duration.ofDays(1)), NOW);
		long b = account("KAKAO", "1002", NOW.minus(Duration.ofDays(1)), NOW);
		long c = account("KAKAO", "1003", NOW.minus(Duration.ofDays(1)), NOW);
		long d = account("NAVER", "naver-1", NOW.minus(Duration.ofDays(1)), NOW);
		fake.kakaoIdPages = List.of("{\"elements\":[1001],\"after_url\":\"https://kapi.kakao.com/v1/user/ids?next\"}",
				"{\"elements\":[1003],\"after_url\":null}");

		assertThat(cleanup.checkKakaoLinks()).isEqualTo(1);

		assertThat(exists(a)).isTrue();
		assertThat(exists(b)).isFalse();
		assertThat(exists(c)).isTrue();
		assertThat(exists(d)).isTrue();
		assertThat(events.stream(AccountDeleted.class).map(e -> e.accountId().value())).containsExactly(b);
		assertThat(fake.kakaoIdsRequests).hasSize(2);
		assertThat(fake.kakaoIdsRequests.get(0)).contains("limit=100", "order=asc").doesNotContain("from_id");
		assertThat(fake.kakaoIdsRequests.get(1)).contains("from_id=1001");
		// Already unlinked at Kakao: no unlink call
		assertThat(fake.unlinkRequests).isEmpty();
	}

	@Test
	void aFailedPageDeletesNothing() {
		long b = account("KAKAO", "1002", NOW.minus(Duration.ofDays(1)), NOW);
		fake.kakaoIdPages = List.of("{\"elements\":[1001],\"after_url\":\"next\"}", "{\"elements\":[1003],\"after_url\":null}");
		fake.kakaoIdsFailAt = 2;

		assertThat(cleanup.checkKakaoLinks()).isZero();

		assertThat(exists(b)).isTrue();
		assertThat(events.stream(AccountDeleted.class)).isEmpty();
	}

	@Test
	void anErrorOnTheFirstPageDeletesNothing() {
		long b = account("KAKAO", "1002", NOW.minus(Duration.ofDays(1)), NOW);
		fake.kakaoIdsFailAt = 1;
		fake.kakaoIdsFailStatus = 401;

		assertThat(cleanup.checkKakaoLinks()).isZero();
		assertThat(exists(b)).isTrue();
	}

	@Test
	void anEmptyListDeletesNothing() {
		long b = account("KAKAO", "1002", NOW.minus(Duration.ofDays(1)), NOW);
		fake.kakaoIdPages = List.of("{\"elements\":[],\"after_url\":null}");

		assertThat(cleanup.checkKakaoLinks()).isZero();
		assertThat(exists(b)).isTrue();
	}

	@Test
	void loginsMadeAfterTheCheckStartedAreLeftOut() {
		long old = account("KAKAO", "2001", NOW.minus(Duration.ofDays(1)), NOW);
		long fresh = account("KAKAO", "2002", NOW.plusSeconds(30), NOW);
		fake.kakaoIdPages = List.of("{\"elements\":[9999],\"after_url\":null}");

		assertThat(cleanup.checkKakaoLinks()).isEqualTo(1);

		assertThat(exists(old)).isFalse();
		assertThat(exists(fresh)).isTrue();
	}

	@Test
	void oneYearBoundaryADayEachSide() {
		Instant yearAgo = NOW.atOffset(ZoneOffset.UTC).minusYears(1).toInstant();
		long stale = account("NAVER", "naver-old", yearAgo.minus(Duration.ofDays(400)), yearAgo.minus(Duration.ofDays(1)));
		long recent = account("NAVER", "naver-new", yearAgo.minus(Duration.ofDays(400)), yearAgo.plus(Duration.ofDays(1)));

		assertThat(cleanup.deleteInactiveAccounts()).isEqualTo(1);

		assertThat(exists(stale)).isFalse();
		assertThat(exists(recent)).isTrue();
		// Naver: no token, nothing to revoke
		assertThat(fake.unlinkRequests).isEmpty();
		assertThat(events.stream(AccountDeleted.class).map(e -> e.accountId().value())).containsExactly(stale);
	}

	@Test
	void inactiveKakaoAccountIsUnlinkedAndDeletedEvenWhenUnlinkFails() {
		Instant longAgo = NOW.minus(Duration.ofDays(800));
		fake.unlinkStatus = 500;
		long kakao = account("KAKAO", "3001", longAgo, longAgo);

		assertThat(cleanup.deleteInactiveAccounts()).isEqualTo(1);

		assertThat(fake.unlinkRequests).singleElement().asString().contains("target_id=3001");
		assertThat(exists(kakao)).isFalse();
	}

	private long account(String method, String subject, Instant createdAt, Instant lastLogin) {
		long id = jdbc.sql("INSERT INTO auth.account (created_at, last_login_at) VALUES (?, ?) RETURNING id")
			.params(Timestamp.from(createdAt), Timestamp.from(lastLogin))
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject, created_at) VALUES (?, ?, ?, ?)")
			.params(id, method, subject, Timestamp.from(createdAt))
			.update();
		return id;
	}

	private boolean exists(long account) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.account WHERE id = ?)").param(account).query(Boolean.class).single();
	}

}
