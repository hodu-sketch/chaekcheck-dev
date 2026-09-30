package com.chaekcheck.fetch;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.net.ssl.CertPathTrustManagerParameters;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * TLS for calls to library sites (same as the main service's {@code LibrarySiteTls}; keep the two in step). Some sites send their certificate without the intermediate certificate, so the
 * JDK cannot build a chain to a root (PKIX error; docs/library-site-issues.md 3절). This context trusts exactly the
 * JDK's own root certificates, and only adds the missing intermediates ({@code tls/library-intermediates/*.pem},
 * downloaded from the addresses in the sites' certificates) as material for building the chain. An intermediate is
 * never a trust anchor: a chain must still end at a JDK root, and the host name is still checked by the HTTP client
 * (user decision 2026-09-29: certificate checking stays on). Revocation is not checked, as in the JDK default.
 */
final class LibrarySiteTls {

	static final String INTERMEDIATES = "classpath:tls/library-intermediates/*.pem";

	private LibrarySiteTls() {
	}

	static SSLContext sslContext() {
		try {
			PKIXBuilderParameters params = new PKIXBuilderParameters(jdkRoots(), new X509CertSelector());
			params.setRevocationEnabled(false);
			params.addCertStore(CertStore.getInstance("Collection",
					new CollectionCertStoreParameters(intermediates())));
			TrustManagerFactory factory = TrustManagerFactory.getInstance("PKIX");
			factory.init(new CertPathTrustManagerParameters(params));
			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, factory.getTrustManagers(), null);
			return context;
		}
		catch (GeneralSecurityException ex) {
			throw new IllegalStateException("could not build the TLS context for library sites", ex);
		}
	}

	static Set<TrustAnchor> jdkRoots() throws GeneralSecurityException {
		TrustManagerFactory jdk = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
		jdk.init((KeyStore) null);
		Set<TrustAnchor> anchors = new HashSet<>();
		for (X509Certificate root : ((X509TrustManager) jdk.getTrustManagers()[0]).getAcceptedIssuers()) {
			anchors.add(new TrustAnchor(root, null));
		}
		return anchors;
	}

	static List<X509Certificate> intermediates() throws GeneralSecurityException {
		CertificateFactory factory = CertificateFactory.getInstance("X.509");
		List<X509Certificate> certificates = new ArrayList<>();
		try {
			for (Resource resource : new PathMatchingResourcePatternResolver().getResources(INTERMEDIATES)) {
				try (InputStream in = resource.getInputStream()) {
					certificates.add((X509Certificate) factory.generateCertificate(in));
				}
			}
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return certificates;
	}

}
