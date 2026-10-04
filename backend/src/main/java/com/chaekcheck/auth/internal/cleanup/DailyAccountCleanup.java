package com.chaekcheck.auth.internal.cleanup;

import java.time.Clock;
import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.auth.internal.AuthProperties;
import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;
import com.chaekcheck.auth.internal.account.AccountDeletion;
import com.chaekcheck.auth.internal.account.AccountStore;
import com.chaekcheck.auth.internal.account.AccountStore.LoginMethodRef;
import com.chaekcheck.auth.internal.login.KakaoLinkedUsers;
import com.chaekcheck.auth.internal.login.LoginProvider;
import com.chaekcheck.auth.internal.login.ProviderUnlinker;

/**
 * The two daily account jobs (handoff step 10), at {@code chaekcheck.auth.cleanup.cron} (04:00 Asia/Seoul). Both
 * delete through the one deletion place, so each deleted account publishes {@code AccountDeleted}. Login off: no
 * scheduling at all (C32).
 */
@Component
@ConditionalOnAuthEnabled
class DailyAccountCleanup {

	private static final Logger log = LoggerFactory.getLogger(DailyAccountCleanup.class);

	private final AuthProperties properties;

	private final AccountStore accounts;

	private final AccountDeletion deletion;

	private final KakaoLinkedUsers kakaoUsers;

	private final ProviderUnlinker unlinker;

	private final Clock clock;

	@Autowired
	DailyAccountCleanup(AuthProperties properties, AccountStore accounts, AccountDeletion deletion,
			KakaoLinkedUsers kakaoUsers, ProviderUnlinker unlinker) {
		this(properties, accounts, deletion, kakaoUsers, unlinker, Clock.systemUTC());
	}

	DailyAccountCleanup(AuthProperties properties, AccountStore accounts, AccountDeletion deletion,
			KakaoLinkedUsers kakaoUsers, ProviderUnlinker unlinker, Clock clock) {
		this.properties = properties;
		this.accounts = accounts;
		this.deletion = deletion;
		this.kakaoUsers = kakaoUsers;
		this.unlinker = unlinker;
		this.clock = clock;
	}

	@Scheduled(cron = "${chaekcheck.auth.cleanup.cron}", zone = "${chaekcheck.auth.cleanup.zone}")
	void runDaily() {
		if (properties.enabled(LoginProvider.KAKAO)) {
			checkKakaoLinks();
		}
		deleteInactiveAccounts();
	}

	/**
	 * C29: deletes the Kakao accounts that are no longer linked at Kakao (a missed unlink webhook). Only when the whole
	 * list was read; login methods made after the reading started are left out (a sign-up during the run). Returns
	 * the number of accounts deleted.
	 */
	int checkKakaoLinks() {
		Instant started = clock.instant();
		Optional<Set<String>> linked = kakaoUsers.all();
		if (linked.isEmpty()) {
			log.warn("Kakao link check: the user list was not read to the end; nothing deleted today");
			return 0;
		}
		List<LoginMethodRef> ours = accounts.kakaoLoginsCreatedBefore(started);
		if (linked.get().isEmpty() && !ours.isEmpty()) {
			// A wrong key or app would also give an empty list; deleting every Kakao account on that is not undoable
			log.warn("Kakao link check: Kakao lists no linked user but we have {}; nothing deleted", ours.size());
			return 0;
		}
		int deleted = 0;
		for (LoginMethodRef login : ours) {
			if (!linked.get().contains(login.subject()) && deletion.delete(login.accountId())) {
				deleted++;
			}
		}
		log.info("Kakao link check: {} linked at Kakao, {} accounts deleted", linked.get().size(), deleted);
		return deleted;
	}

	/**
	 * C30: deletes accounts whose last login is more than {@code inactive-after} (1 year) ago. Kakao accounts are
	 * unlinked first with the admin key (a failure is only logged); Naver has no token to revoke and is skipped.
	 * Returns the number of accounts deleted.
	 */
	int deleteInactiveAccounts() {
		Period inactiveAfter = properties.cleanup().inactiveAfter();
		Instant cutoff = clock.instant().atOffset(ZoneOffset.UTC).minus(inactiveAfter).toInstant();
		int deleted = 0;
		for (long accountId : accounts.lastLoginBefore(cutoff)) {
			accounts.subjectOf(accountId, LoginProvider.KAKAO.method()).ifPresent(unlinker::unlinkKakao);
			if (deletion.delete(accountId)) {
				deleted++;
			}
		}
		log.info("Inactive account clean-up: {} accounts deleted (last login before {})", deleted, cutoff);
		return deleted;
	}

}
