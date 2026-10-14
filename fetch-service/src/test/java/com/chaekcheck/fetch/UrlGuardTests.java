package com.chaekcheck.fetch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * Host names with an underscore (cycle 18, ADR-0111): read from the authority, then resolved and checked like any
 * other name. A fake DNS lookup decides where each name points.
 */
class UrlGuardTests {

	static final Map<String, String> DNS = Map.of(
			"hs_partners.yes24library.com", "203.0.113.10",
			"hs_inside.example.test", "10.0.0.7",
			"hs_loop.example.test", "127.0.0.1",
			"ebook.example.test", "203.0.113.11");

	final List<String> lookedUp = new ArrayList<>();

	UrlGuard guard = new UrlGuard(properties(false), host -> {
		lookedUp.add(host);
		String address = DNS.get(host);
		if (address == null) {
			throw new UnknownHostException(host);
		}
		return new InetAddress[] { InetAddress.getByName(address) };
	});

	@Test
	void underscoreHostOnAPublicAddressPassesAndTheConnectNameIsLookedUp() {
		assertThat(guard.check("https://hs_partners.yes24library.com/search/?src_key=1984"))
			.hasToString("https://hs_partners.yes24library.com/search/?src_key=1984");
		assertThat(lookedUp).containsExactly("hs_partners.yes24library.com");
	}

	@Test
	void underscoreNameResolvingToAPrivateAddressIsRefused() {
		assertThatThrownBy(() -> guard.check("https://hs_inside.example.test/"))
			.isInstanceOf(UrlGuard.BadUrlException.class)
			.hasMessageContaining("non-public");
		assertThatThrownBy(() -> guard.check("http://hs_loop.example.test:8080/"))
			.isInstanceOf(UrlGuard.BadUrlException.class)
			.hasMessageContaining("non-public");
	}

	@Test
	void userInfoIsRefusedForUnderscoreHostsToo() {
		// a registry-based authority: getRawUserInfo() is null, the '@' is still seen
		assertThatThrownBy(() -> guard.check("https://user@hs_partners.yes24library.com/"))
			.isInstanceOf(UrlGuard.BadUrlException.class)
			.hasMessageContaining("user info");
		assertThatThrownBy(() -> guard.check("https://user@ebook.example.test/"))
			.isInstanceOf(UrlGuard.BadUrlException.class);
		assertThat(lookedUp).isEmpty();
	}

	@Test
	void unknownUnderscoreNameIsRefused() {
		assertThatThrownBy(() -> guard.check("https://hs_nowhere.example.test/"))
			.isInstanceOf(UrlGuard.BadUrlException.class)
			.hasMessageContaining("does not resolve");
	}

	@Test
	void ordinaryHostsAreCheckedAsBefore() {
		assertThat(guard.check("https://ebook.example.test/x")).hasToString("https://ebook.example.test/x");
		assertThatThrownBy(() -> guard.check("https://bad host/")).isInstanceOf(UrlGuard.BadUrlException.class);
	}

	static FetchProperties properties(boolean allowLoopback) {
		return new FetchProperties("X-Fetch-Token", "t", "chaekcheck-fetch/test", Duration.ofSeconds(1),
				Duration.ofMillis(500), Duration.ofSeconds(1), DataSize.ofBytes(1024), 4, allowLoopback);
	}

}
