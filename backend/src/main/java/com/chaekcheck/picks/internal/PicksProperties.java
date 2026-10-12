package com.chaekcheck.picks.internal;

import java.net.URI;
import java.time.Duration;
import java.time.Period;
import java.time.ZoneId;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.util.unit.DataSize;

/**
 * Settings of the librarian picks (plan cycle 14 design-delta "설정·인증키"). Values live in application.yml.
 * @param fetch fetching from the National Library of Korea
 * @param cache the shared cache (ADR-0090)
 */
@ConfigurationProperties("chaekcheck.picks")
public record PicksProperties(Fetch fetch, Cache cache) {

	/**
	 * @param enabled false: no fetch at start-up and none scheduled (tests, the collection import, local development);
	 * the cache and the API still work
	 * @param apiKey the Open API key, a secret: never logged ({@link #toString()} hides it); empty means no request
	 * @param cron when the daily fetch decision runs [확정 05:00]
	 * @param zone zone of {@code cron}, of "today" and of the Monday rule (Asia/Seoul)
	 * @param baseUrl the Open API host, without a trailing slash
	 * @param window registration dates asked for: from today minus this to today [확정 2y]
	 * @param endRow last row asked for [확정 1000]
	 * @param connectTimeout connecting [확정 5s]
	 * @param readTimeout until the response headers [확정 10s]; the whole request, body included, may take
	 * {@code connectTimeout + readTimeout}
	 * @param maxBodySize largest list answer read [확정 10MB]
	 * @param coverMaxBodySize largest cover image read before it is made smaller [확정 5MB]
	 * @param requestInterval least time between the end of one request and the start of the next [확정 1s]
	 * @param userAgent sent with every request
	 * @param cover how covers are stored
	 */
	public record Fetch(boolean enabled, String apiKey, String cron, ZoneId zone, URI baseUrl, Period window, int endRow,
			Duration connectTimeout, Duration readTimeout, DataSize maxBodySize, DataSize coverMaxBodySize,
			Duration requestInterval, String userAgent, Cover cover) {

		/** Whether a key is set; the fetch sends no request without one ({@code key_missing}). */
		public boolean hasApiKey() {
			return apiKey != null && !apiKey.isBlank();
		}

		@Override
		public String toString() {
			return "Fetch[enabled=" + enabled + ", apiKey=" + (hasApiKey() ? "***" : "(none)") + ", cron=" + cron
					+ ", zone=" + zone + ", baseUrl=" + baseUrl + ", window=" + window + ", endRow=" + endRow
					+ ", connectTimeout=" + connectTimeout + ", readTimeout=" + readTimeout + ", maxBodySize="
					+ maxBodySize + ", coverMaxBodySize=" + coverMaxBodySize + ", requestInterval=" + requestInterval
					+ ", userAgent=" + userAgent + ", cover=" + cover + "]";
		}

	}

	/**
	 * Every cover is stored and served as one size (ADR-0091).
	 * @param width stored width in pixels; narrower images keep their width [확정 200]
	 * @param jpegQuality JPEG quality 0 to 1 [확정 0.8]
	 * @param maxPixels largest width x height decoded; a bigger image fails that cover [확정 16,000,000]
	 */
	public record Cover(int width, float jpegQuality, long maxPixels) {
	}

	/** @param refreshInterval how often the cache is read again from the database [확정 10m] */
	public record Cache(Duration refreshInterval) {
	}

}
