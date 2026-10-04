package com.chaekcheck.auth.internal.token;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

import com.chaekcheck.auth.internal.ConditionalOnAuthEnabled;

/**
 * Deletes expired refresh rows once a day (C31), at {@code chaekcheck.auth.cleanup.cron} (04:00 Asia/Seoul).
 * Scheduling is switched on here, so no job runs while login is off (C32).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnAuthEnabled
@EnableScheduling
class RefreshTokenCleanup {

	private static final Logger log = LoggerFactory.getLogger(RefreshTokenCleanup.class);

	private final TokenService tokens;

	RefreshTokenCleanup(TokenService tokens) {
		this.tokens = tokens;
	}

	@Scheduled(cron = "${chaekcheck.auth.cleanup.cron}", zone = "${chaekcheck.auth.cleanup.zone}")
	void deleteExpired() {
		log.info("Deleted {} expired refresh tokens", tokens.deleteExpired());
	}

}
