package com.chaekcheck.picks.internal.fetch;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Optional;

/**
 * Which cover addresses are fetched (ADR-0091) [확정]: the API's {@code recomfilepath} with http made https, then only
 * host exactly {@code www.nl.go.kr} with a path under {@code /afile/}. Addresses with user info, a port, a fragment, a
 * {@code ..} segment or another scheme are refused [안]. A refused address is stored as no cover (NULL).
 */
public final class CoverUrls {

	static final String HOST = "www.nl.go.kr";

	static final String PATH_PREFIX = "/afile/";

	private CoverUrls() {
	}

	/** @return {@code https://www.nl.go.kr/afile/...}, or empty when the address is not allowed */
	public static Optional<String> normalize(String raw) {
		if (raw == null || raw.isBlank()) {
			return Optional.empty();
		}
		URI uri;
		try {
			uri = new URI(raw.strip());
		}
		catch (URISyntaxException ex) {
			return Optional.empty();
		}
		String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!scheme.equals("http") && !scheme.equals("https")) {
			return Optional.empty();
		}
		if (uri.getRawUserInfo() != null || uri.getPort() != -1 || uri.getRawFragment() != null
				|| !HOST.equals(uri.getHost())) {
			return Optional.empty();
		}
		String path = uri.getRawPath();
		String decoded = uri.getPath();
		if (path == null || !path.startsWith(PATH_PREFIX) || decoded == null || decoded.contains("..")
				|| path.contains("\\")) {
			return Optional.empty();
		}
		return Optional.of("https://" + HOST + path + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
	}

}
