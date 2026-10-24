package com.chaekcheck.aisearch.internal.web;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The client IP for the AI search's own limits (cycle 24 step 0 #6): read here from {@code X-Real-IP} (nginx
 * {@code proxy_set_header X-Real-IP $remote_addr} after {@code real_ip_header CF-Connecting-IP}), because the usage
 * log's copy is empty while logging is off. The same check as {@code usagelog}'s: only an IPv4 or IPv6 literal, never
 * a name lookup. Never stored (ADR-0152).
 */
final class ClientIp {

	static final String REAL_IP = "X-Real-IP";

	private static final Pattern IPV4 = Pattern.compile(
			"((25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)\\.){3}(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)");

	private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

	private ClientIp() {
	}

	/** The address, or null when the header is missing or not an IP literal. */
	static String of(HttpServletRequest request) {
		String value = request.getHeader(REAL_IP);
		if (value == null) {
			return null;
		}
		String ip = value.strip();
		if (IPV4.matcher(ip).matches()) {
			return ip;
		}
		if (ip.indexOf(':') >= 0 && IPV6_CHARACTERS.matcher(ip).matches()) {
			try {
				return InetAddress.getByName(ip).getHostAddress();
			}
			catch (UnknownHostException ex) {
				return null;
			}
		}
		return null;
	}

}
