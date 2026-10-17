package com.chaekcheck.fetch;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * One way out to the library sites, the same lane list as the main service (webshare cycle (in cycle 20), ADR-0130, handoff 1-15 #5):
 * {@code direct} (this server's IP) and {@code proxy-01}, {@code proxy-02} … one per entry of {@code fetch.egress.proxies}
 * in order. The main service picks the lane and names it in the request; this service only maps the id to the
 * proxy. No shared code between the two applications on purpose (ADR-0011, cycle 18 decision #4): the parsing is
 * repeated here and must stay the same shape as the main service's {@code EgressLane}.
 * @param proxy null for the direct lane
 */
record EgressLane(String id, Kind kind, String vendor, InetSocketAddress proxy) {

	static final String DIRECT_ID = "direct";

	static final EgressLane DIRECT = new EgressLane(DIRECT_ID, Kind.DIRECT, DIRECT_ID, null);

	enum Kind {

		DIRECT, PROXY

	}

	boolean isDirect() {
		return kind == Kind.DIRECT;
	}

	/** Direct first, then one proxy lane per {@code host:port} entry; the same ids as the main service. */
	static List<EgressLane> list(String proxies, String vendor) {
		List<EgressLane> lanes = new ArrayList<>();
		lanes.add(DIRECT);
		String vendorName = vendor == null || vendor.isBlank() ? "webshare" : vendor.strip().toLowerCase(Locale.ROOT);
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
					throw new IllegalArgumentException("fetch.egress.proxies: entry " + (n + 1) + " is not host:port");
				}
				n++;
				lanes.add(new EgressLane(String.format(Locale.ROOT, "proxy-%02d", n), Kind.PROXY, vendorName,
						InetSocketAddress.createUnresolved(entry.substring(0, colon), port)));
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
