package com.chaekcheck.collection.internal;

import java.net.URI;
import java.util.Locale;

/**
 * Host names of library and book addresses for the collection import and the library insert (cycle 18, ADR-0111).
 * Read by hand instead of {@link URI#getHost()}, which gives no host for names with {@code _} (2624
 * {@code hs_partners.yes24library.com}; Java {@code URI}: a label holds letters, digits and hyphens only). Moved here
 * from {@code LibraryInsertPlanner.host} so both use one rule.
 * <p>
 * The availability module reads hosts with its own class; modules do not share this code (cycle 18 design decision
 * #4).
 */
public final class SiteHosts {

	private SiteHosts() {
	}

	/**
	 * Host name without scheme, user, port and path, in lower case; null when there is none (null or blank URL, or
	 * nothing between the scheme and the path).
	 */
	public static String host(String url) {
		String rest = url == null ? "" : url.strip();
		int scheme = rest.indexOf("://");
		if (scheme >= 0) {
			rest = rest.substring(scheme + 3);
		}
		int end = rest.length();
		for (char stop : new char[] { '/', '?', '#' }) {
			int at = rest.indexOf(stop);
			if (at >= 0 && at < end) {
				end = at;
			}
		}
		rest = rest.substring(0, end);
		int user = rest.lastIndexOf('@');
		if (user >= 0) {
			rest = rest.substring(user + 1);
		}
		int port = rest.lastIndexOf(':');
		if (port >= 0 && !rest.endsWith("]")) {
			rest = rest.substring(0, port);
		}
		return rest.isEmpty() ? null : rest.toLowerCase(Locale.ROOT);
	}

	/** Host of an http(s) URL ({@link #host}); null for any other scheme or a URL that cannot be read. */
	public static String httpHost(String url) {
		if (url == null) {
			return null;
		}
		try {
			String scheme = URI.create(url.strip()).getScheme();
			if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
				return null;
			}
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
		return host(url);
	}

}
