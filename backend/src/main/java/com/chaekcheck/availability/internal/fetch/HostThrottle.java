package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.time.Instant;
import java.util.concurrent.Callable;

/**
 * Call spacing per host name (ADR-0010): at most one call at a time, and a call starts no earlier than the
 * configured interval after the previous call to the same host name ended. The port is ignored, so
 * {@code ebook.jbe.go.kr:10112} and {@code :10124} share one slot. Waiting counts toward the caller's deadline; a
 * call that cannot start before it fails with TIMEOUT.
 * <p>
 * Behind an interface so a shared implementation (e.g. Redis) can replace the in-memory one when the main service
 * runs on several instances (plan-feedback C2-U-1).
 */
public interface HostThrottle {

	/**
	 * @throws com.chaekcheck.availability.internal.UpstreamException TIMEOUT when the call cannot start before
	 * {@code deadline}, ERROR when the call throws a checked exception
	 */
	<T> T run(URI uri, Instant deadline, Callable<T> call);

	/**
	 * The throttle key: host name in lower case, port ignored. Host names with an underscore are read too (cycle 18,
	 * {@link SiteHostNames}).
	 * @throws com.chaekcheck.availability.internal.UpstreamException ERROR when the address has no host
	 */
	static String key(URI uri) {
		return SiteHostNames.host(uri);
	}

	/** host:port for logs. */
	static String hostPort(URI uri) {
		return SiteHostNames.hostOrNull(uri) + ":" + SiteHostNames.port(uri);
	}

}
