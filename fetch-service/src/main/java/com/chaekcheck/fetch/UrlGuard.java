package com.chaekcheck.fetch;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

import org.springframework.stereotype.Component;

/**
 * Rejects URLs this service must not fetch (SSRF): schemes other than http/https, and hosts that resolve to a
 * private, loopback, link-local, any-local, multicast or IPv6 unique-local address. Checked on the addresses
 * after DNS resolution, so a public name pointing at a private address is rejected too.
 * <p>
 * The HTTP client resolves the name again when it connects, so a name whose DNS answer changes between the two
 * lookups (DNS rebinding) is not fully covered. The shared token and running on a private network limit who can
 * try that.
 */
@Component
class UrlGuard {

	private final boolean allowLoopback;

	UrlGuard(FetchProperties properties) {
		this.allowLoopback = properties.allowLoopback();
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
		if (uri.getHost() == null || uri.getRawUserInfo() != null) {
			throw new BadUrlException("host required, no user info");
		}
		InetAddress[] addresses;
		try {
			addresses = InetAddress.getAllByName(uri.getHost());
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

	static class BadUrlException extends RuntimeException {

		BadUrlException(String message) {
			super(message);
		}

	}

}
