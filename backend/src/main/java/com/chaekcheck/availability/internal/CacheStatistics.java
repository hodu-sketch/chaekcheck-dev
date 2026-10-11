package com.chaekcheck.availability.internal;

import com.github.benmanes.caffeine.cache.stats.CacheStats;

/**
 * A cache whose statistics are logged (plan ADR-0066, cycle 08 handoff step 5-4): hits, misses, evictions and the
 * entry count, read to choose each cache's size limit. Caffeine counts them once {@code recordStats()} is set; an
 * {@code AsyncCache} gives them through its {@code synchronous()} view (Caffeine wiki "Statistics", checked
 * 2026-10-11 with Context7).
 */
public interface CacheStatistics {

	/** Short name for the log, e.g. {@code search-page}. */
	String cacheName();

	CacheStats stats();

	/** Entries now (approximate). */
	long size();

}
