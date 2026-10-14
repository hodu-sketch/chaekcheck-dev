package com.chaekcheck.usagelog.internal;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.regex.Pattern;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpHeaders;

/**
 * What the log reads from a request: the client IP that nginx put in {@code X-Real-IP} (ADR-0097) and whether the
 * User-Agent names a robot (spec R1-5). The User-Agent itself is never kept (AC-12).
 */
public final class RequestFacts {

	static final String REAL_IP = "X-Real-IP";

	private static final Pattern ROBOT = Pattern.compile("bot|crawler|spider");

	private static final Pattern IPV4 = Pattern
		.compile("((25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\.){3}(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])");

	private static final Pattern IPV6_CHARACTERS = Pattern.compile("[0-9A-Fa-f:.]{2,45}");

	private RequestFacts() {
	}

	/** {@code bot}, {@code crawler} or {@code spider} anywhere in the User-Agent, any case. */
	public static boolean isRobot(HttpServletRequest request) {
		String agent = request.getHeader(HttpHeaders.USER_AGENT);
		return agent != null && ROBOT.matcher(agent.toLowerCase(Locale.ROOT)).find();
	}

	/** The {@code X-Real-IP} value when it is an IPv4 or IPv6 address, otherwise null. Never looks up a name. */
	public static String clientIp(HttpServletRequest request) {
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
				// A literal with a colon is parsed as IPv6 only, no name lookup
				return InetAddress.getByName(ip).getHostAddress();
			}
			catch (UnknownHostException ex) {
				return null;
			}
		}
		return null;
	}

}
