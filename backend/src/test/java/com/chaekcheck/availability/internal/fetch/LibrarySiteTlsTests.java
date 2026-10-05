package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * Missing intermediates help build a chain but are never trusted on their own; roots the JDK lacks are trusted only
 * when the user approved them (docs/library-site-issues.md 3절).
 */
class LibrarySiteTlsTests {

	@Test
	void loadsTheIntermediatesThatAreNotRoots() throws Exception {
		List<X509Certificate> intermediates = LibrarySiteTls.certificates(LibrarySiteTls.INTERMEDIATES);

		// 6 (2026-09-29) + Sectigo RSA OV, GeoTrust TLS RSA CA G1, Sectigo R46 cross-signed by USERTrust (2026-10-05)
		assertThat(intermediates).hasSize(9);
		// every one is issued by someone else, so none is self-signed
		assertThat(intermediates).allSatisfy(
				cert -> assertThat(cert.getIssuerX500Principal()).isNotEqualTo(cert.getSubjectX500Principal()));
	}

	@Test
	void theOnlyAddedRootIsTheApprovedOne() throws Exception {
		List<X509Certificate> roots = LibrarySiteTls.certificates(LibrarySiteTls.ROOTS);

		// user decision 2026-10-05 ("이거 신뢰해줘"): OISTE WISeKey Global Root GB CA, for 3 sites
		assertThat(roots).singleElement()
			.satisfies(root -> assertThat(root.getSubjectX500Principal().getName())
				.contains("CN=OISTE WISeKey Global Root GB CA"))
			.satisfies(root -> assertThat(root.getIssuerX500Principal()).isEqualTo(root.getSubjectX500Principal()));
	}

	@Test
	void everyIntermediateEndsAtATrustedRoot() throws Exception {
		Set<TrustAnchor> anchors = LibrarySiteTls.jdkRoots();
		List<X509Certificate> intermediates = LibrarySiteTls.certificates(LibrarySiteTls.INTERMEDIATES);

		assertThat(anchors).isNotEmpty();
		assertThat(anchors).extracting(TrustAnchor::getTrustedCert).doesNotContainAnyElementsOf(intermediates);
		// each intermediate's issuer is a JDK root or another intermediate (the R46 cross certificate), so chains
		// through them still end at a JDK root
		assertThat(intermediates).allSatisfy(cert -> assertThat(Stream.concat(
				anchors.stream().map(TrustAnchor::getTrustedCert), intermediates.stream()))
			.anySatisfy(issuer -> assertThat(issuer.getSubjectX500Principal()).isEqualTo(cert.getIssuerX500Principal())));
	}

	@Test
	void buildsAContext() {
		assertThat(LibrarySiteTls.sslContext().getProtocol()).isEqualTo("TLS");
	}

}
