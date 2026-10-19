package com.chaekcheck.availability.internal.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The lookup state recorded in {@code availability.lookup_sample} (user decision 2026-10-19 "B: DB에 저장";
 * docs/maintenance/settings-index.md).
 * @param enabled false = no sampler bean, nothing written (the test profile turns it off) [안 true]
 * @param interval one row per this long [사용자 10s]
 * @param retention month partitions that end before now minus this are dropped, once a day [안 90d]
 */
@ConfigurationProperties("chaekcheck.availability.ops-sample")
public record OpsSampleProperties(boolean enabled, Duration interval, Duration retention) {
}
