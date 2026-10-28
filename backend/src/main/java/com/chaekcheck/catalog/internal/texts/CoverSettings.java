package com.chaekcheck.catalog.internal.texts;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code chaekcheck.covers.*} (cycle 26, ADR-0163).
 * @param baseUrl the CloudFront address the covers are under, without the closing {@code /}; empty gives no cover
 *     address ({@code CHAEKCHECK_COVERS_BASEURL}; docs/maintenance/settings-index.md)
 */
@ConfigurationProperties("chaekcheck.covers")
public record CoverSettings(String baseUrl) {

	public CoverSettings {
		baseUrl = baseUrl == null ? "" : baseUrl.strip();
		while (baseUrl.endsWith("/")) {
			baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
		}
		if (!baseUrl.isEmpty() && !baseUrl.startsWith("https://")) {
			throw new IllegalArgumentException("chaekcheck.covers.base-url must start with https://: " + baseUrl);
		}
	}

}
