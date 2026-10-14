package com.chaekcheck.fetch;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Rejects URLs this service must not fetch (SSRF): schemes other than http/https, and hosts that resolve to a
 * private, loopback, link-local, any-local, multicast or IPv6 unique-local address. Checked on the addresses
 * after DNS resolution, so a public name pointing at a private address is rejected too.
 * <p>
 * The HTTP client resolves the name again when it connects, so a name whose DNS answer changes between the two
 * lookups (DNS rebinding) is not fully covered. The shared token and running on a private network limit who can
 * try that.
 * <p>
 * Cycle 18: a host name with an underscore ({@code getHost()} is null) is read from the authority
 * ({@link UnderscoreHosts}); the same name is resolved and checked here, and user info is refused for it too.
 */
@Component
class UrlGuard {

	private final boolean allowLoopback;

	private final HostResolver resolver;

	@Autowired
	UrlGuard(FetchProperties properties) {
		this(properties, InetAddress::getAllByName);
	}

	/** With a DNS lookup of its own, for tests (an underscore name pointing at a private address). */
	UrlGuard(FetchProperties properties, HostResolver resolver) {
		this.allowLoopback = properties.allowLoopback();
		this.resolver = resolver;
	}

	/** @throws BadUrlException when the URL must not be fetched */
	URI check(String url) {
		URI uri;
		try {
			uri = new URI(url);
		}
		catch (URISyntaxException | NullPointerException ex) {
			throw new BadUrlException("not a URL");
		}
		String scheme = uri.getScheme();
		if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
			throw new BadUrlException("only http and https");
		}
		// the name LibraryClient connects to; host names with an underscore are read too (cycle 18, ADR-0111)
		String host = UnderscoreHosts.host(uri);
		if (host == null || UnderscoreHosts.hasUserInfo(uri)) {
			throw new BadUrlException("host required, no user info");
		}
		InetAddress[] addresses;
		try {
			addresses = resolver.resolve(host);
		}
		catch (UnknownHostException ex) {
			throw new BadUrlException("host does not resolve");
		}
		for (InetAddress address : addresses) {
			if (isForbidden(address)) {
				throw new BadUrlException("host resolves to a non-public address");
			}
		}
		return uri;
	}

	private boolean isForbidden(InetAddress address) {
		if (address.isLoopbackAddress()) {
			return !allowLoopback;
		}
		return address.isSiteLocalAddress() || address.isLinkLocalAddress() || address.isAnyLocalAddress()
				|| address.isMulticastAddress() || isUniqueLocalV6(address) || isSharedAddressSpace(address);
	}

	/** fc00::/7 */
	private static boolean isUniqueLocalV6(InetAddress address) {
		return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
	}

	/** 100.64.0.0/10 (carrier-grade NAT) */
	private static boolean isSharedAddressSpace(InetAddress address) {
		byte[] b = address.getAddress();
		return b.length == 4 && (b[0] & 0xFF) == 100 && (b[1] & 0xC0) == 64;
	}

	/** DNS lookup of a host name. */
	@FunctionalInterface
	interface HostResolver {

		InetAddress[] resolve(String host) throws UnknownHostException;

	}

	static class BadUrlException extends RuntimeException {

		BadUrlException(String message) {
			super(message);
		}

	}

}
