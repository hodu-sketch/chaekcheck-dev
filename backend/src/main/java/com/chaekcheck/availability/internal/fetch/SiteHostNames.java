package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.util.Locale;

import com.chaekcheck.availability.internal.UpstreamException;

/**
 * Reads the host name of a library site address (cycle 18, ADR-0111). {@link URI#getHost()} only accepts letters,
 * digits and hyphens in a label, so for {@code https://hs_partners.yes24library.com} it is null (the URI keeps a
 * registry-based authority). Then the host is read from the raw authority: user info and port are cut off and the
 * rest is taken as it is, underscores included. The name is in lower case either way.
 * <p>
 * Kept inside availability; the collection module reads hosts with its own class (no shared code between modules,
 * cycle 18 design decision #4).
 */
public final class SiteHostNames {

	private SiteHostNames() {
	}

	/**
	 * @return the host name in lower case
	 * @throws UpstreamException ERROR when the address has no host at all, so only that library's row fails
	 */
	public static String host(URI uri) {
		String host = hostOrNull(uri);
		if (host == null) {
			throw UpstreamException.error("no host in " + uri);
		}
		return host;
	}

	/** Same as {@link #host}, but null when the address has no host. */
	public static String hostOrNull(URI uri) {
		if (uri.getHost() != null) {
			return uri.getHost().toLowerCase(Locale.ROOT);
		}
		String authority = uri.getRawAuthority();
		if (authority == null) {
			return null;
		}
		String host = authority.substring(authority.lastIndexOf('@') + 1);
		if (host.startsWith("[")) {
			// an IPv6 literal always parses as a server-based authority, so getHost() would not have been null
			return null;
		}
		int colon = host.indexOf(':');
		if (colon >= 0) {
			host = host.substring(0, colon);
		}
		return host.isEmpty() ? null : host.toLowerCase(Locale.ROOT);
	}

	/**
	 * True for an address {@link java.net.http} cannot send: {@code getHost()} is null and the host read from the
	 * authority has an underscore. Such addresses go through {@link UnderscoreHostSiteFetcher}.
	 */
	public static boolean needsUnderscorePath(URI uri) {
		if (uri.getHost() != null) {
			return false;
		}
		String host = hostOrNull(uri);
		return host != null && host.indexOf('_') >= 0;
	}

	/** The port the request goes to: the address's own, or the scheme's default. */
	static int port(URI uri) {
		if (uri.getPort() != -1) {
			return uri.getPort();
		}
		String authority = uri.getRawAuthority();
		if (uri.getHost() == null && authority != null) {
			String hostPort = authority.substring(authority.lastIndexOf('@') + 1);
			int colon = hostPort.indexOf(':');
			if (colon >= 0) {
				try {
					return Integer.parseInt(hostPort.substring(colon + 1));
				}
				catch (NumberFormatException ex) {
					// not a port; fall back to the scheme's default
				}
			}
		}
		return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
	}

}
