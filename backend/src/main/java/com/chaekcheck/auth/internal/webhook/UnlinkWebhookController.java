package com.chaekcheck.auth.internal.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.ErrorResponseException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountDeletion;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.login.LoginProvider;

/**
 * The providers tell us that a user ended the link on their side (handoff step 9, AC-13, AC-14). These requests
 * come from the providers' servers, so they are outside CSRF (C4) and prove themselves instead: Kakao by the admin
 * key and app number, Naver by its signature. A genuine request deletes the account of that provider number through
 * the one deletion place (with {@code AccountDeleted}); an unknown number is answered the same way, so the call
 * is idempotent (C19). A provider that is off ({@code chaekcheck.auth.providers}) has no webhook (404).
 */
@RestController
@ConditionalOnAuthEnabled
class UnlinkWebhookController {

	private static final Logger log = LoggerFactory.getLogger(UnlinkWebhookController.class);

	private final AuthProperties properties;

	private final AccountStore accounts;

	private final AccountDeletion deletion;

	private final NaverUnlinkVerifier naver;

	UnlinkWebhookController(AuthProperties properties, AccountStore accounts, AccountDeletion deletion) {
		this.properties = properties;
		this.accounts = accounts;
		this.deletion = deletion;
		this.naver = properties.enabled(LoginProvider.NAVER)
				? new NaverUnlinkVerifier(properties.naver().clientId(), properties.naver().clientSecret()) : null;
	}

	/**
	 * Kakao "연결 끊기 알림": {@code Authorization: KakaoAK <primary admin key>}, form {@code app_id}, {@code user_id},
	 * {@code referrer_type}. 200 within 3 seconds, also for an unknown user; 401 when the key or app does not match.
	 */
	@PostMapping("/api/v1/auth/webhooks/kakao-unlink")
	ResponseEntity<Void> kakaoUnlink(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
			@RequestParam(name = "app_id", required = false) String appId,
			@RequestParam(name = "user_id", required = false) String userId) {
		requireEnabled(LoginProvider.KAKAO);
		if (!same(authorization, "KakaoAK " + properties.kakao().adminKey()) || !same(appId, properties.kakao().appId())
				|| userId == null || userId.isBlank()) {
			throw unauthorized();
		}
		deleteAccountOf(LoginProvider.KAKAO, userId);
		return ResponseEntity.ok().build();
	}

	/**
	 * Naver "연결끊기 Callback": form {@code clientId}, {@code encryptUniqueId}, {@code timestamp}, {@code signature}.
	 * 204 without a body, also for an unknown user; 401 when the request is not genuine. Naver does not retry.
	 */
	@PostMapping("/api/v1/auth/webhooks/naver-unlink")
	ResponseEntity<Void> naverUnlink(@RequestParam(required = false) String clientId,
			@RequestParam(required = false) String encryptUniqueId, @RequestParam(required = false) String timestamp,
			@RequestParam(required = false) String signature) {
		requireEnabled(LoginProvider.NAVER);
		Optional<String> userId = naver.verify(clientId, encryptUniqueId, timestamp, signature, Instant.now());
		if (userId.isEmpty()) {
			throw unauthorized();
		}
		deleteAccountOf(LoginProvider.NAVER, userId.get());
		return ResponseEntity.noContent().build();
	}

	/** One login method per account in this cycle, so the account always goes (handoff step 9). */
	private void deleteAccountOf(LoginProvider provider, String subject) {
		accounts.findByLoginMethod(provider.method(), subject).ifPresent(accountId -> {
			if (deletion.delete(accountId)) {
				log.info("Provider unlink from {}: account {} deleted", provider, accountId);
			}
		});
	}

	private void requireEnabled(LoginProvider provider) {
		if (!properties.enabled(provider)) {
			throw problem(HttpStatus.NOT_FOUND, "not_found", "No such webhook");
		}
	}

	private static boolean same(String given, String expected) {
		return given != null && expected != null && MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
				expected.getBytes(StandardCharsets.UTF_8));
	}

	private static ErrorResponseException unauthorized() {
		return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Request not verified");
	}

	private static ErrorResponseException problem(HttpStatus status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return new ErrorResponseException(status, problem, null);
	}

}
