package com.chaekcheck.fetch;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Lets TLS connections use RSA key exchange ({@code TLS_RSA_*} cipher suites) again. JDK 24+ disables them, but some
 * library sites accept nothing else (Daegu e-library: TLS 1.2 + {@code TLS_RSA_WITH_AES_256_GCM_SHA384}, checked
 * 2026-09-29). Only that entry is removed from {@code jdk.tls.disabledAlgorithms}; certificates are still verified
 * and every other restriction stays (user decision 2026-09-29). This service only calls library
 * sites and sends nothing but the URL (search text).
 * <p>
 * Must run before the first TLS connection, so it is called at the start of {@code main}.
 */
final class TlsCompatibility {

	static final String PROPERTY = "jdk.tls.disabledAlgorithms";

	private TlsCompatibility() {
	}

	static void allowRsaKeyExchange() {
		String current = Security.getProperty(PROPERTY);
		if (current == null) {
			return;
		}
		Security.setProperty(PROPERTY, Arrays.stream(current.split(","))
			.map(String::strip)
			.filter(entry -> !entry.equals("TLS_RSA_*"))
			.collect(Collectors.joining(", ")));
	}

}
