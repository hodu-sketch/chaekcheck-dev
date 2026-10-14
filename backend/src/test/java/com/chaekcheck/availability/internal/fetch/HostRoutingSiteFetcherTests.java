package com.chaekcheck.availability.internal.fetch;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Underscore hosts go to the new path, every other address stays on the direct path (cycle 18). */
class HostRoutingSiteFetcherTests {

	final List<URI> direct = new ArrayList<>();

	final List<URI> underscore = new ArrayList<>();

	HostRoutingSiteFetcher fetcher = new HostRoutingSiteFetcher(recording(direct, "direct"),
			recording(underscore, "direct-underscore"));

	@Test
	void underscoreHostGoesToTheUnderscorePath() {
		SiteResponse response = fetcher.fetch(URI.create("https://hs_partners.yes24library.com/search/?src_key=1984"),
				Instant.MAX);

		assertThat(response.via()).isEqualTo("direct-underscore");
		assertThat(underscore).hasSize(1);
		assertThat(direct).isEmpty();
	}

	@Test
	void ordinaryHostsStayOnTheDirectPath() {
		fetcher.fetch(URI.create("https://ebook.gangseo.seoul.kr/search/"), Instant.MAX);
		fetcher.fetch(URI.create("https://ebook.jbe.go.kr:10112/x"), Instant.MAX);
		fetcher.fetch(URI.create("http://127.0.0.1:8080/x"), Instant.MAX);

		assertThat(direct).hasSize(3);
		assertThat(underscore).isEmpty();
	}

	private static SiteFetcher recording(List<URI> calls, String via) {
		return (uri, deadline) -> {
			calls.add(uri);
			return new SiteResponse(200, null, null, new byte[0], via);
		};
	}

}
