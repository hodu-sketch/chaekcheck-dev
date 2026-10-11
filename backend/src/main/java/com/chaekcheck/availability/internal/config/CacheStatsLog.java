package com.chaekcheck.availability.internal.config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.availability.internal.CacheStatistics;
import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * Writes one log line per cache every interval (plan ADR-0066, no Actuator): hit rate, hits, misses, evictions and
 * entries since the start. Step 9 reads these lines to choose each cache's size limit. Its own daemon thread, so it
 * does not depend on Spring scheduling being on.
 */
final class CacheStatsLog implements AutoCloseable {

	private static final Logger log = LoggerFactory.getLogger(CacheStatsLog.class);

	private final List<CacheStatistics> caches;

	private final ScheduledExecutorService timer;

	CacheStatsLog(List<CacheStatistics> caches, Duration interval) {
		this.caches = List.copyOf(caches);
		this.timer = Executors.newSingleThreadScheduledExecutor(Thread.ofPlatform()
			.name("cache-stats")
			.daemon(true)
			.factory());
		long millis = interval.toMillis();
		timer.scheduleAtFixedRate(this::logNow, millis, millis, TimeUnit.MILLISECONDS);
	}

	void logNow() {
		for (CacheStatistics cache : caches) {
			CacheStats stats = cache.stats();
			log.info("cache {}: hitRate={} hits={} misses={} evictions={} entries={}", cache.cacheName(),
					String.format(java.util.Locale.ROOT, "%.3f", stats.hitRate()), stats.hitCount(),
					stats.missCount(), stats.evictionCount(), cache.size());
		}
	}

	@Override
	public void close() {
		timer.shutdownNow();
	}

}
