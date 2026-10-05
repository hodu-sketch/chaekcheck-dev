package com.chaekcheck.fetch;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Fetch service settings. Values live in application.yml; the token comes from the environment.
 * @param tokenHeader header that carries the shared token
 * @param token shared token; when empty every request is refused with 401
 * @param userAgent same value as the main service's {@code chaekcheck.availability.user-agent}
 * @param readTimeout until the library's response headers arrive
 * @param totalTimeout the whole library request, body included; past it the call is cancelled (TIMEOUT)
 * @param maxBodySize largest library body read; a bigger one stops being read and the call fails (ERROR)
 * @param maxConcurrent requests handled at once; more get 503
 * @param allowLoopback only for tests with a local fake library; never in real configuration
 */
@ConfigurationProperties("fetch")
public record FetchProperties(String tokenHeader, String token, String userAgent, Duration connectTimeout,
		Duration readTimeout, Duration totalTimeout, DataSize maxBodySize, int maxConcurrent, boolean allowLoopback) {
}
