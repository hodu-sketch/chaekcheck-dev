package com.chaekcheck.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Step 9 (AC-13, AC-14): the providers' unlink notices, sent here as hand-made requests. The Naver request is built
 * with the algorithm of Naver's guide (plan research.md 3절) and a test-only client secret.
 */
@SpringBootTest(properties = { "chaekcheck.auth.enabled=true",
		"chaekcheck.auth.public-base-url=http://localhost:5173", "chaekcheck.auth.kakao.admin-key=test-kakao-admin-key",
		"chaekcheck.auth.kakao.app-id=123456", "chaekcheck.auth.naver.client-id=test-naver-id",
		"chaekcheck.auth.naver.client-secret=test-naver-secret", "chaekcheck.auth.kakao.client-id=test-kakao-id",
		"chaekcheck.auth.kakao.client-secret=test-kakao-secret" })
@AutoConfigureMockMvc
@Transactional
class UnlinkWebhookTests {

	@DynamicPropertySource
	static void jwtKey(DynamicPropertyRegistry registry) {
		byte[] key = new byte[32];
		new SecureRandom().nextBytes(key);
		registry.add("chaekcheck.auth.jwt.secret", () -> Base64.getEncoder().encodeToString(key));
	}

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	@Test
	void genuineNaverNoticeDeletesTheAccountAndIsIdempotent() {
		long account = account("NAVER", "naver-unlink-1");
		NaverNotice notice = NaverNotice.of("test-naver-id", "test-naver-secret", "naver-unlink-1", Instant.now());

		assertThat(naver(notice)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(accountExists(account)).isFalse();
		// Sent again (or for a user we do not know): still 204
		assertThat(naver(notice)).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(naver(NaverNotice.of("test-naver-id", "test-naver-secret", "never-signed-up", Instant.now())))
			.hasStatus(HttpStatus.NO_CONTENT);
	}

	@Test
	void naverNoticeWithAWrongSignatureIsRefused() {
		long account = account("NAVER", "naver-unlink-2");
		NaverNotice genuine = NaverNotice.of("test-naver-id", "test-naver-secret", "naver-unlink-2", Instant.now());

		assertUnauthorized(naver(new NaverNotice(genuine.clientId(), genuine.encryptUniqueId(), genuine.timestamp(),
				genuine.signature().substring(1) + "A")));
		// Signed with another secret
		assertUnauthorized(naver(NaverNotice.of("test-naver-id", "someone-else", "naver-unlink-2", Instant.now())));
		assertThat(accountExists(account)).isTrue();
	}

	@Test
	void naverNoticeForAnotherAppIsRefused() {
		long account = account("NAVER", "naver-unlink-3");

		assertUnauthorized(naver(NaverNotice.of("another-client", "test-naver-secret", "naver-unlink-3", Instant.now())));
		assertThat(accountExists(account)).isTrue();
	}

	@Test
	void naverNoticeOlderOrNewerThanFiveMinutesIsRefused() {
		long account = account("NAVER", "naver-unlink-4");

		assertUnauthorized(naver(NaverNotice.of("test-naver-id", "test-naver-secret", "naver-unlink-4",
				Instant.now().minus(Duration.ofMinutes(5)).minusSeconds(5))));
		assertUnauthorized(naver(NaverNotice.of("test-naver-id", "test-naver-secret", "naver-unlink-4",
				Instant.now().plus(Duration.ofMinutes(5)).plusSeconds(5))));
		assertThat(accountExists(account)).isTrue();
		// Within the five minutes it goes through
		assertThat(naver(NaverNotice.of("test-naver-id", "test-naver-secret", "naver-unlink-4",
				Instant.now().minus(Duration.ofMinutes(4))))).hasStatus(HttpStatus.NO_CONTENT);
		assertThat(accountExists(account)).isFalse();
	}

	@Test
	void naverNoticeWithMissingFieldsIsRefused() {
		assertUnauthorized(mvc.post()
			.uri("/api/v1/auth/webhooks/naver-unlink")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.content("clientId=test-naver-id")
			.exchange());
	}

	@Test
	void genuineKakaoNoticeDeletesTheAccountQuickly() {
		long account = account("KAKAO", "424242");

		long started = System.nanoTime();
		MvcTestResult result = kakao("KakaoAK test-kakao-admin-key", "123456", "424242");
		Duration took = Duration.ofNanos(System.nanoTime() - started);

		assertThat(result).hasStatus(HttpStatus.OK);
		assertThat(accountExists(account)).isFalse();
		assertThat(took).isLessThan(Duration.ofSeconds(3));
	}

	@Test
	void kakaoNoticeForAnUnknownUserIsStillOk() {
		assertThat(kakao("KakaoAK test-kakao-admin-key", "123456", "999999")).hasStatus(HttpStatus.OK);
	}

	@Test
	void kakaoNoticeWithAWrongKeyOrAppIsRefused() {
		long account = account("KAKAO", "424243");

		assertUnauthorized(kakao("KakaoAK wrong-key", "123456", "424243"));
		assertUnauthorized(kakao(null, "123456", "424243"));
		assertUnauthorized(kakao("KakaoAK test-kakao-admin-key", "654321", "424243"));
		assertThat(accountExists(account)).isTrue();
	}

	private MvcTestResult naver(NaverNotice notice) {
		// No CSRF token: providers cannot send one
		return mvc.post()
			.uri("/api/v1/auth/webhooks/naver-unlink")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("clientId", notice.clientId())
			.param("encryptUniqueId", notice.encryptUniqueId())
			.param("timestamp", notice.timestamp())
			.param("signature", notice.signature())
			.exchange();
	}

	private MvcTestResult kakao(String authorization, String appId, String userId) {
		var request = mvc.post()
			.uri("/api/v1/auth/webhooks/kakao-unlink")
			.contentType(MediaType.APPLICATION_FORM_URLENCODED)
			.param("app_id", appId)
			.param("user_id", userId)
			.param("referrer_type", "UNLINK_FROM_APPS");
		if (authorization != null) {
			request = request.header(HttpHeaders.AUTHORIZATION, authorization);
		}
		return request.exchange();
	}

	private static void assertUnauthorized(MvcTestResult result) {
		assertThat(result).hasStatus(HttpStatus.UNAUTHORIZED).bodyJson().extractingPath("$.code").isEqualTo("unauthorized");
	}

	private long account(String method, String subject) {
		long id = jdbc.sql("INSERT INTO auth.account (last_login_at) VALUES (now()) RETURNING id")
			.query(Long.class)
			.single();
		jdbc.sql("INSERT INTO auth.login_method (account_id, method, subject) VALUES (?, ?, ?)")
			.params(id, method, subject)
			.update();
		return id;
	}

	private boolean accountExists(long account) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM auth.account WHERE id = ?)").param(account).query(Boolean.class).single();
	}

	/** A Naver unlink notice made as Naver's guide 4.4 describes (sample code: Base64 URL-safe). */
	record NaverNotice(String clientId, String encryptUniqueId, String timestamp, String signature) {

		static NaverNotice of(String clientId, String clientSecret, String userId, Instant at) {
			try {
				byte[] key = Arrays.copyOf(MessageDigest.getInstance("MD5")
					.digest(clientSecret.getBytes(StandardCharsets.UTF_8)), 16);
				byte[] iv = new byte[16];
				new SecureRandom().nextBytes(iv);
				Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
				cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
				byte[] encrypted = cipher.doFinal(userId.getBytes(StandardCharsets.UTF_8));
				byte[] joined = new byte[iv.length + encrypted.length];
				System.arraycopy(iv, 0, joined, 0, iv.length);
				System.arraycopy(encrypted, 0, joined, iv.length, encrypted.length);
				String encryptUniqueId = Base64.getUrlEncoder().withoutPadding().encodeToString(joined);
				String timestamp = String.valueOf(at.getEpochSecond());
				Mac mac = Mac.getInstance("HmacSHA256");
				mac.init(new SecretKeySpec(key, "HmacSHA256"));
				String signature = Base64.getUrlEncoder()
					.withoutPadding()
					.encodeToString(mac.doFinal(("clientId=" + clientId + "&encryptUniqueId=" + encryptUniqueId
							+ "&timestamp=" + timestamp).getBytes(StandardCharsets.UTF_8)));
				return new NaverNotice(clientId, encryptUniqueId, timestamp, signature);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

}
