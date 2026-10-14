package com.chaekcheck.fetch;

import java.net.URI;
import java.util.Locale;

/**
 * Host names with an underscore (cycle 18, ADR-0111; today only {@code hs_partners.yes24library.com}).
 * {@link URI#getHost()} is null for them, because a label may only hold letters, digits and hyphens; the URI keeps a
 * registry-based authority instead. java.net.http refuses such a URI, so {@link LibraryClient} sends it with Apache
 * HttpClient 5, which connects to the host read from the authority. {@link UrlGuard} checks that same name.
 * <p>
 * The main service has its own copy of this rule ({@code availability} {@code SiteHostNames}); this is a separate
 * application, so the code is not shared.
 */
final class UnderscoreHosts {

	private UnderscoreHosts() {
	}

	/**
	 * The host name the request connects to, in lower case: {@code getHost()}, or for an underscore host the name
	 * read from the raw authority (port cut off). Null when there is no host, or when a registry-based authority
	 * has user info or no underscore (not a name this service reads).
	 */
	static String host(URI uri) {
		if (uri.getHost() != null) {
			return uri.getHost().toLowerCase(Locale.ROOT);
		}
		String authority = uri.getRawAuthority();
		if (authority == null || authority.indexOf('@') >= 0 || authority.startsWith("[")) {
			return null;
		}
		int colon = authority.indexOf(':');
		String host = (colon >= 0 ? authority.substring(0, colon) : authority).toLowerCase(Locale.ROOT);
		return !host.isEmpty() && host.indexOf('_') >= 0 ? host : null;
	}

	/** True when {@code getHost()} is null and the authority holds a readable underscore host name. */
	static boolean isUnderscoreHost(URI uri) {
		return uri.getHost() == null && host(uri) != null;
	}

	/** True when the authority has user info, also for a registry-based authority (where getRawUserInfo() is null). */
	static boolean hasUserInfo(URI uri) {
		return uri.getRawUserInfo() != null || (uri.getRawAuthority() != null && uri.getRawAuthority().indexOf('@') >= 0);
	}

}
