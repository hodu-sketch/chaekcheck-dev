package com.chaekcheck.popular.internal;

import java.net.URI;
import java.time.Duration;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the popular books (plan cycle 10 design-delta "설정"). Values live in application.yml.
 * @param fetch fetching from the publishing distribution network
 * @param cache the shared cache of the latest month (ADR-0074)
 */
@ConfigurationProperties("chaekcheck.popular")
public record PopularProperties(Fetch fetch, Cache cache) {

	/**
	 * @param enabled false: no fetch at start-up and none scheduled (tests, the collection import, local
	 * development); the cache and the API still work
	 * @param cron when the daily fetch decision runs [확정 05:00]
	 * @param zone zone of {@code cron} and of the month boundaries (Asia/Seoul)
	 * @param requestInterval least time between the end of one request to the site and the start of the next
	 * [확정 2s]
	 * @param connectTimeout connecting to the site [확정 5s]
	 * @param readTimeout until the response headers [확정 10s]; the whole request, body included, may take
	 * {@code connectTimeout + readTimeout}
	 * @param baseUrl the site, without a trailing slash
	 * @param userAgent sent with every request
	 * @param maxBodySize largest page or POST answer read; a bigger body fails the fetch ({@code format_changed}) [안]
	 * @param coverMaxBodySize largest cover image read, before it is made smaller; a bigger one fails that cover
	 * (user decision 2026-10-12: 50MB)
	 * @param cover how covers are stored
	 */
	public record Fetch(boolean enabled, String cron, ZoneId zone, Duration requestInterval, Duration connectTimeout,
			Duration readTimeout, URI baseUrl, String userAgent, DataSize maxBodySize, DataSize coverMaxBodySize,
			Cover cover) {
	}

	/**
	 * Every cover is stored and served as one size (user decision 2026-10-12, "표지 크기를 하나로 맞춤").
	 * @param width stored width in pixels; narrower images keep their width [확정 200]
	 * @param jpegQuality JPEG quality 0 to 1 [안 0.8]
	 * @param maxPixels largest width x height decoded; a bigger image fails that cover, so one image cannot fill the
	 * heap (512MB) [안 16,000,000]
	 */
	public record Cover(int width, float jpegQuality, long maxPixels) {
	}

	/** @param refreshInterval how often the cache is read again from the database [확정 10m] */
	public record Cache(Duration refreshInterval) {
	}

}
