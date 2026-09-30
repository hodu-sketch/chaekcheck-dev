package com.chaekcheck.fetch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fetch service settings. Values live in application.yml; the token comes from the environment.
 * @param tokenHeader header that carries the shared token
 * @param token shared token; when empty every request is refused with 401
 * @param userAgent same value as the main service's {@code chaekcheck.availability.user-agent}
 * @param maxConcurrent requests handled at once; more get 503
 * @param allowLoopback only for tests with a local fake library; never in real configuration
 */
@ConfigurationProperties("fetch")
public record FetchProperties(String tokenHeader, String token, String userAgent, Duration connectTimeout,
		Duration readTimeout, int maxConcurrent, boolean allowLoopback) {
}
