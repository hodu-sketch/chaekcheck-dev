package com.chaekcheck.availability.internal.adapter;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.fetch.SiteHostNames;

/**
 * Every adapter builds its search address on a site root whose host has an underscore (cycle 18 A2-4, spec R3-3).
 * Only 2624 (YES24_V2) has such a host today; the others are checked so a future one does not fail on the address.
 */
class UnderscoreHostSearchUriTests {

	static final String HOST = "hs_partners.example.com";

	static Stream<PlatformAdapter> adapters() {
		return Stream.of(new AladinV1Adapter(), new BookcubeFxAdapter(), new ElibPortalV1Adapter(),
				new GangnamV1Adapter(), new KyoboSubscriptionV1Adapter(AdapterFixtures.PROPERTIES_10),
				new KyoboV1Adapter(AdapterFixtures.PROPERTIES), new Yes24V1Adapter(AdapterFixtures.PROPERTIES_10),
				new Yes24V2Adapter(AdapterFixtures.PROPERTIES), new Yes24V3Adapter(AdapterFixtures.PROPERTIES));
	}

	@ParameterizedTest
	@MethodSource("adapters")
	void searchUriKeepsTheUnderscoreHost(PlatformAdapter adapter) {
		// ALADIN_V1 reads the e-book menu from the site root's nav_code
		String base = "https://" + HOST + (adapter instanceof AladinV1Adapter ? "/?nav_code=ebook" : "/elib");
		URI uri = adapter.searchUri(base, SearchQuery.of("1984"), 1);

		assertThat(SiteHostNames.host(uri)).isEqualTo(HOST);
		assertThat(SiteHostNames.needsUnderscorePath(uri)).isTrue();
		assertThat(SiteHostNames.host(adapter.detailUri(uri, "1"))).isEqualTo(HOST);
	}

}
