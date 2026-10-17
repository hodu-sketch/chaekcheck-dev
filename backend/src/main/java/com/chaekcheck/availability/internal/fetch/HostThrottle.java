package com.chaekcheck.availability.internal.fetch;

import java.net.URI;

/**
 * Throttle keys of library addresses. The call spacing itself is per lane and host since cycle 22
 * ({@link com.chaekcheck.availability.internal.egress.LaneThrottle}, ADR-0010 made per lane by ADR-0095/0130).
 */
public final class HostThrottle {

	private HostThrottle() {
	}

	/**
	 * The throttle key: host name in lower case, port ignored. Host names with an underscore are read too (cycle 18,
	 * {@link SiteHostNames}).
	 * @throws com.chaekcheck.availability.internal.UpstreamException ERROR when the address has no host
	 */
	public static String key(URI uri) {
		return SiteHostNames.host(uri);
	}

	/** host:port for logs. */
	public static String hostPort(URI uri) {
		return SiteHostNames.hostOrNull(uri) + ":" + SiteHostNames.port(uri);
	}

}
