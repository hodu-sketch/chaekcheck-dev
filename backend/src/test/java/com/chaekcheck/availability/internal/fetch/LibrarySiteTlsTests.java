package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** Missing intermediates help build a chain but are never trusted on their own (docs/library-site-issues.md 3절). */
class LibrarySiteTlsTests {

	@Test
	void loadsTheSixIntermediatesThatAreNotRoots() throws Exception {
		List<X509Certificate> intermediates = LibrarySiteTls.intermediates();

		assertThat(intermediates).hasSize(6);
		// every one is issued by someone else (a root in the JDK), so none is self-signed
		assertThat(intermediates).allSatisfy(
				cert -> assertThat(cert.getIssuerX500Principal()).isNotEqualTo(cert.getSubjectX500Principal()));
	}

	@Test
	void trustAnchorsAreOnlyTheJdkRoots() throws Exception {
		Set<TrustAnchor> anchors = LibrarySiteTls.jdkRoots();

		assertThat(anchors).isNotEmpty();
		assertThat(anchors).extracting(TrustAnchor::getTrustedCert)
			.doesNotContainAnyElementsOf(LibrarySiteTls.intermediates());
		// each intermediate's issuer is a JDK root, so chains through them still end at a JDK root
		assertThat(LibrarySiteTls.intermediates()).allSatisfy(cert -> assertThat(anchors)
			.anySatisfy(anchor -> assertThat(anchor.getTrustedCert().getSubjectX500Principal())
				.isEqualTo(cert.getIssuerX500Principal())));
	}

	@Test
	void buildsAContext() {
		assertThat(LibrarySiteTls.sslContext().getProtocol()).isEqualTo("TLS");
	}

}
