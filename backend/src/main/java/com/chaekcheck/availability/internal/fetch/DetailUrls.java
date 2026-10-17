package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.util.Locale;

/**
 * The one rule for a detail URL this service may request (cycle 20 AC-18, server mode too): http(s), and the same
 * host name as the library's site root. A URL that fails it is dropped (null) where the page is read
 * ({@link LibraryPageFetcher}) and where a holding is planned, so it is neither shown nor requested, and the cache
 * never holds it either.
 */
public final class DetailUrls {

	private DetailUrls() {
	}

	/**
	 * @return the URL when it may be requested; null otherwise
	 */
	public static URI checked(String detailUrl, String baseUrl) {
		if (detailUrl == null) {
			return null;
		}
		try {
			URI uri = URI.create(detailUrl.strip());
			URI base = URI.create(baseUrl);
			String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
			if (!scheme.equals("http") && !scheme.equals("https")) {
				return null;
			}
			// host names with an underscore are compared too (cycle 18, ADR-0111)
			String host = SiteHostNames.hostOrNull(uri);
			if (host == null || !host.equals(SiteHostNames.hostOrNull(base))) {
				return null;
			}
			return uri;
		}
		catch (RuntimeException ex) {
			return null;
		}
	}

}
