package com.chaekcheck.availability.internal.fetch;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.adapter.AdapterFixtures;
import com.chaekcheck.availability.internal.adapter.Yes24V2Adapter;

/**
 * One-off probe of the real 2624 site (cycle 18 A2-1, user approved 2026-10-14). Never runs in the build: only with
 * {@code CHAEKCHECK_UNDERSCORE_PROBE=1}. Sends at most three requests, several seconds apart (host-min-interval and
 * human pace; docs/library-maintenance.md 2절). Run with {@code -Djavax.net.debug=ssl:handshake} to see that the
 * ClientHello carries no server_name.
 */
@EnabledIfEnvironmentVariable(named = "CHAEKCHECK_UNDERSCORE_PROBE", matches = "1")
class UnderscoreHostProbe {

	static final String BASE = "https://hs_partners.yes24library.com";

	@Test
	void probe() throws Exception {
		System.setProperty("javax.net.debug", "ssl:handshake");
		URI base = URI.create(BASE);
		System.out.println("PROBE getHost=" + base.getHost() + " rawAuthority=" + base.getRawAuthority()
				+ " SiteHostNames.host=" + SiteHostNames.host(base) + " needsUnderscorePath="
				+ SiteHostNames.needsUnderscorePath(base));
		try {
			HttpRequest.newBuilder(base).GET().build();
			System.out.println("PROBE java.net.http newBuilder: accepted");
		}
		catch (RuntimeException ex) {
			System.out.println("PROBE java.net.http newBuilder: " + ex);
		}
		try {
			System.out.println("PROBE HostThrottle.key=" + HostThrottle.key(base));
		}
		catch (RuntimeException ex) {
			System.out.println("PROBE HostThrottle.key: " + ex);
		}
		URI search;
		try {
			search = new Yes24V2Adapter(AdapterFixtures.PROPERTIES).searchUri(BASE, SearchQuery.of("1984"), 1);
			System.out.println("PROBE Yes24V2 searchUri=" + search + " getHost=" + search.getHost());
		}
		catch (RuntimeException ex) {
			System.out.println("PROBE Yes24V2 searchUri: " + ex);
			search = URI.create(BASE + "/search/?src_key=1984&srch_order=title&page_num=1&sort=1");
		}

		try (UnderscoreHostSiteFetcher fetcher = UnderscoreHostSiteFetcher.create(LibrarySiteTls.sslContext(),
				Duration.ofSeconds(5), Duration.ofSeconds(5), Duration.ofSeconds(15), 4 * 1024 * 1024,
				"chaekcheck/0.0.1", Clock.systemUTC())) {
			for (URI uri : List.of(base, search)) {
				long started = System.nanoTime();
				try {
					SiteResponse response = fetcher.fetch(uri, Instant.now().plusSeconds(20));
					String body = new String(response.body(), java.nio.charset.StandardCharsets.UTF_8);
					int at = body.indexOf("1984");
					System.out.println("PROBE fetch " + uri + " -> " + response + " in "
							+ (System.nanoTime() - started) / 1_000_000 + "ms; contains 1984 at " + at
							+ (at >= 0 ? ": " + body.substring(Math.max(0, at - 80), Math.min(body.length(), at + 80))
								.replaceAll("\\s+", " ") : ""));
				}
				catch (RuntimeException ex) {
					System.out.println("PROBE fetch " + uri + " failed in " + (System.nanoTime() - started) / 1_000_000
							+ "ms: " + ex + (ex.getCause() != null ? " cause " + ex.getCause() : ""));
				}
				Thread.sleep(5_000);
			}
		}
	}

}
