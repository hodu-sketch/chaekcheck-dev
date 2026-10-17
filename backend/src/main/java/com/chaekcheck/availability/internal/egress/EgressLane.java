package com.chaekcheck.availability.internal.egress;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One way out to the library sites (webshare cycle (in cycle 20) / handoff 1-15, ADR-0130): the server's own IP ({@link Kind#DIRECT},
 * id {@code direct}) or an HTTP proxy ({@link Kind#PROXY}, ids {@code proxy-01}, {@code proxy-02} … in list order)
 * such as one of the Webshare Korean static residential addresses. A server with several public IPs is expressed
 * as proxy lanes too (tinyproxy {@code Bind} per IP, multi-source-ip 6절 #2), so there is one code path for both.
 * <p>
 * The proxy address is never part of the id and never logged: logs carry {@code lane=proxy-03 vendor=webshare}.
 * @param vendor {@code direct} for the direct lane, else {@code egress.proxy-vendor} (default {@code webshare})
 * @param proxy the proxy address (unresolved); null for the direct lane
 */
public record EgressLane(String id, Kind kind, String vendor, InetSocketAddress proxy) {

	public static final String DIRECT_ID = "direct";

	public static final EgressLane DIRECT = new EgressLane(DIRECT_ID, Kind.DIRECT, DIRECT_ID, null);

	public enum Kind {

		DIRECT, PROXY

	}

	public boolean isDirect() {
		return kind == Kind.DIRECT;
	}

	/** The n-th proxy lane (1-based) of {@code vendor} at {@code host:port}. */
	public static EgressLane proxy(int n, String vendor, String host, int port) {
		return new EgressLane(String.format(Locale.ROOT, "proxy-%02d", n), Kind.PROXY, vendor,
				InetSocketAddress.createUnresolved(host, port));
	}

	/**
	 * The full lane list: {@code direct} first, then one proxy lane per entry of the {@code egress.proxies} setting
	 * (comma separated {@code host:port}, blanks ignored).
	 * @throws IllegalArgumentException when an entry is not {@code host:port}
	 */
	public static List<EgressLane> list(String proxies, String vendor) {
		List<EgressLane> lanes = new ArrayList<>();
		lanes.add(DIRECT);
		String vendorName = vendor == null || vendor.isBlank() ? "webshare" : vendor.strip().toLowerCase(Locale.ROOT);
		if (vendorName.equals(DIRECT_ID) || !vendorName.matches("[a-z0-9-]+")) {
			throw new IllegalArgumentException("egress proxy-vendor must be letters, digits or '-', not 'direct'");
		}
		if (proxies != null) {
			int n = 0;
			for (String raw : proxies.split(",")) {
				String entry = raw.strip();
				if (entry.isEmpty()) {
					continue;
				}
				int colon = entry.lastIndexOf(':');
				int port;
				try {
					port = colon < 0 ? -1 : Integer.parseInt(entry.substring(colon + 1));
				}
				catch (NumberFormatException ex) {
					port = -1;
				}
				if (colon < 1 || port < 1 || port > 65535 || entry.indexOf('/') >= 0 || entry.indexOf('@') >= 0) {
					// the message names the position, not the value, so a wrong entry does not land in a log
					throw new IllegalArgumentException("egress proxies: entry " + (n + 1) + " is not host:port");
				}
				lanes.add(proxy(++n, vendorName, entry.substring(0, colon), port));
			}
		}
		return List.copyOf(lanes);
	}

	/** No address, so a lane can be logged. */
	@Override
	public String toString() {
		return id;
	}

}
