package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;

import org.junit.jupiter.api.Test;

import com.chaekcheck.availability.internal.UpstreamException;

/** Host names of library addresses, underscores included (cycle 18, ADR-0111). */
class SiteHostNamesTests {

	@Test
	void ordinaryHostIsGetHostInLowerCase() {
		assertThat(SiteHostNames.host(URI.create("https://EBook.Gangseo.seoul.kr/search/?q=1")))
			.isEqualTo("ebook.gangseo.seoul.kr");
		assertThat(SiteHostNames.needsUnderscorePath(URI.create("https://ebook.gangseo.seoul.kr/"))).isFalse();
	}

	@Test
	void underscoreHostIsReadFromTheAuthority() {
		URI uri = URI.create("https://hs_partners.yes24library.com/search/?src_key=1984");

		assertThat(uri.getHost()).isNull();
		assertThat(SiteHostNames.host(uri)).isEqualTo("hs_partners.yes24library.com");
		assertThat(SiteHostNames.needsUnderscorePath(uri)).isTrue();
	}

	@Test
	void portUserInfoAndCaseAreDropped() {
		URI uri = URI.create("http://user:pw@HS_Partners.yes24library.com:8443/x");

		assertThat(SiteHostNames.host(uri)).isEqualTo("hs_partners.yes24library.com");
		assertThat(SiteHostNames.port(uri)).isEqualTo(8443);
		assertThat(SiteHostNames.port(URI.create("https://hs_partners.yes24library.com/"))).isEqualTo(443);
		assertThat(SiteHostNames.port(URI.create("http://hs_partners.yes24library.com/"))).isEqualTo(80);
	}

	@Test
	void hostPortForLogsReadsUnderscoreHosts() {
		assertThat(HostThrottle.hostPort(URI.create("https://hs_partners.yes24library.com/x")))
			.isEqualTo("hs_partners.yes24library.com:443");
		assertThat(HostThrottle.hostPort(URI.create("http://ebook.jbe.go.kr:10112/x"))).isEqualTo("ebook.jbe.go.kr:10112");
	}

	@Test
	void addressWithoutAHostFailsAsAnUpstreamErrorNotANullPointer() {
		URI noHost = URI.create("file:///tmp/x");

		assertThat(SiteHostNames.hostOrNull(noHost)).isNull();
		assertThat(SiteHostNames.needsUnderscorePath(noHost)).isFalse();
		UpstreamException ex = catchThrowableOfType(UpstreamException.class, () -> HostThrottle.key(noHost));
		assertThat(ex.kind()).isEqualTo(UpstreamException.Kind.ERROR);
	}

}
