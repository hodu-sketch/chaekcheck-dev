package com.chaekcheck.collection.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Hosts read by hand for the import and the library insert (cycle 18, ADR-0111). */
class SiteHostsTests {

	@Test
	void underscorePortUserAndCase() {
		assertThat(SiteHosts.host("https://hs_partners.yes24library.com/ebook/detail/?goods_id=1"))
			.isEqualTo("hs_partners.yes24library.com");
		assertThat(SiteHosts.host("http://user@EBOOK.yit.ac.kr:8091/x")).isEqualTo("ebook.yit.ac.kr");
		assertThat(SiteHosts.host("https://EBOOK.yit.ac.kr:446/elibrary-front/")).isEqualTo("ebook.yit.ac.kr");
		assertThat(SiteHosts.host("https://e_book.test/x?y")).isEqualTo("e_book.test");
	}

	@Test
	void noHostIsNull() {
		assertThat(SiteHosts.host(null)).isNull();
		assertThat(SiteHosts.host("")).isNull();
		assertThat(SiteHosts.host("https:///x")).isNull();
	}

	@Test
	void httpHostOnlyForHttpAndHttps() {
		assertThat(SiteHosts.httpHost("HTTPS://hs_partners.yes24library.com/x")).isEqualTo("hs_partners.yes24library.com");
		assertThat(SiteHosts.httpHost("javascript:fnView('1')")).isNull();
		assertThat(SiteHosts.httpHost("/ebook/detail/?goods_id=1")).isNull();
		assertThat(SiteHosts.httpHost("https://bad host/x")).isNull();
		assertThat(SiteHosts.httpHost(null)).isNull();
	}

}
