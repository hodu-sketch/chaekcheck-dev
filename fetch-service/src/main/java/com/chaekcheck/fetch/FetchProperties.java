package com.chaekcheck.fetch;

import java.time.Duration;
import java.util.List;

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
 * @param egress the egress lanes, the same list as the main service (webshare cycle (in cycle 20), handoff 1-15 #5)
 */
@ConfigurationProperties("fetch")
public record FetchProperties(String tokenHeader, String token, String userAgent, Duration connectTimeout,
		Duration readTimeout, Duration totalTimeout, DataSize maxBodySize, int maxConcurrent, boolean allowLoopback,
		Egress egress) {

	public FetchProperties {
		egress = egress == null ? new Egress(null, null, null, null, null) : egress;
	}

	/**
	 * The lanes this server may send through ({@code fetch.egress.*}, env {@code FETCH_EGRESS_*}; the production
	 * compose passes the main service's values, cycle 17).
	 * @param mode {@code all} (default) / {@code proxy-only} / {@code direct-only}: a request naming a lane the mode
	 * excludes is answered {@code outcome=ERROR}
	 * @param proxies comma separated {@code host:port}, {@code proxy-01} … in order; the addresses are secrets
	 * @param proxyVendor the vendor name in logs
	 * @param proxyUsername with {@code proxyPassword}: one pair for every proxy lane; empty = IP authorization
	 */
	public record Egress(Mode mode, String proxies, String proxyVendor, String proxyUsername, String proxyPassword) {

		public Egress {
			mode = mode == null ? Mode.ALL : mode;
		}

		public enum Mode {

			ALL, PROXY_ONLY, DIRECT_ONLY

		}

		/** The lanes the mode allows, in order; empty when the mode leaves none (the application then fails to start). */
		List<EgressLane> allowedLanes() {
			List<EgressLane> all = EgressLane.list(proxies, proxyVendor);
			return switch (mode) {
				case ALL -> all;
				case PROXY_ONLY -> all.stream().filter(lane -> !lane.isDirect()).toList();
				case DIRECT_ONLY -> all.stream().filter(EgressLane::isDirect).toList();
			};
		}

		String[] credentials() {
			return proxyUsername == null || proxyUsername.isBlank() || proxyPassword == null || proxyPassword.isEmpty()
					? null : new String[] { proxyUsername, proxyPassword };
		}

	}

}
