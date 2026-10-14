package com.chaekcheck.usagelog.internal;

import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Usage log settings (cycle 15). Values live in application.yml.
 * @param enabled the whole log [확정 key, default off]: off records nothing and the usage report address still
 * answers 204; on at the public opening day (spec R3-6)
 * @param bufferCapacity rows waiting in memory at most [확정 10,000]; more are dropped and counted
 * @param flushInterval time between the end of one write and the start of the next [확정 5s]
 * @param maxDroppedNotes failed batches remembered until a write succeeds; older notes are merged into the newest
 * @param cleanup the daily clean-up
 */
@ConfigurationProperties("chaekcheck.usagelog")
public record UsageLogProperties(boolean enabled, int bufferCapacity, Duration flushInterval, int maxDroppedNotes,
		Cleanup cleanup) {

	/**
	 * @param cron when it runs [확정 04:30]
	 * @param zone zone of {@code cron} and of the library answer days [확정 Asia/Seoul]
	 * @param batchSize rows changed or deleted per statement
	 */
	public record Cleanup(String cron, ZoneId zone, int batchSize) {
	}

}
