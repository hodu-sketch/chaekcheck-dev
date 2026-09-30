package com.chaekcheck;

import java.security.Security;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Lets TLS connections use RSA key exchange ({@code TLS_RSA_*} cipher suites) again. JDK 24+ disables them, but some
 * library sites accept nothing else (Daegu e-library: TLS 1.2 + {@code TLS_RSA_WITH_AES_256_GCM_SHA384}, checked
 * 2026-09-29). Only that entry is removed from {@code jdk.tls.disabledAlgorithms}; certificates are still verified
 * and every other restriction stays (user decision 2026-09-29).
 * <p>
 * The setting is JVM-wide: every TLS client of this process may then agree to RSA key exchange if the other side
 * picks it, not only the library calls. Today that is only the library calls (the database connection has no TLS in
 * development). If production connects to the database or other services over TLS, those connections are covered
 * too — see docs/plan-feedback.md "추후 과제 전체 목록" for the options (e.g. library calls only through fetch-service).
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
