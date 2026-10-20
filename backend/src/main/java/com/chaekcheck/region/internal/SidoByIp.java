package com.chaekcheck.region.internal;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.zip.GZIPInputStream;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The 시도 a Korean address is in, from DB-IP's free city list cut down to Korea (geo/kr-sido.csv.gz, built by
 * scripts/geo/build_kr_sido.py; CC BY 4.0). Only for the first screen's example card (user 2026-10-20); the address
 * is looked at and dropped, never kept. Read once, on the first ask.
 */
@Component
class SidoByIp {

	static final String RESOURCE = "geo/kr-sido.csv.gz";

	private record Table(long[] v4Starts, long[] v4Ends, String[] v4Sido, BigInteger[] v6Starts, BigInteger[] v6Ends,
			String[] v6Sido) {
	}

	private volatile Table table;

	/** The 시도 short name for an IP literal, or empty: not Korean, not known, or not an IP literal. */
	Optional<String> sido(String ip) {
		InetAddress address = parse(ip);
		if (address == null) {
			return Optional.empty();
		}
		Table loaded = table();
		if (address instanceof Inet4Address) {
			long value = new BigInteger(1, address.getAddress()).longValue();
			int at = floor(loaded.v4Starts(), value);
			return at >= 0 && value <= loaded.v4Ends()[at] ? Optional.of(loaded.v4Sido()[at]) : Optional.empty();
		}
		BigInteger value = new BigInteger(1, address.getAddress());
		int at = floor(loaded.v6Starts(), value);
		return at >= 0 && value.compareTo(loaded.v6Ends()[at]) <= 0 ? Optional.of(loaded.v6Sido()[at])
				: Optional.empty();
	}

	/** An IPv4 or IPv6 literal only: never a name lookup. */
	private static InetAddress parse(String ip) {
		if (ip == null || ip.isBlank() || ip.length() > 45) {
			return null;
		}
		try {
			return InetAddress.ofLiteral(ip.strip());
		}
		catch (IllegalArgumentException notLiteral) {
			return null;
		}
	}

	private static int floor(long[] starts, long value) {
		int at = Arrays.binarySearch(starts, value);
		return at >= 0 ? at : -at - 2;
	}

	private static int floor(BigInteger[] starts, BigInteger value) {
		int at = Arrays.binarySearch(starts, value);
		return at >= 0 ? at : -at - 2;
	}

	private Table table() {
		Table loaded = table;
		if (loaded == null) {
			synchronized (this) {
				if (table == null) {
					table = load();
				}
				loaded = table;
			}
		}
		return loaded;
	}

	private static Table load() {
		List<long[]> v4 = new ArrayList<>();
		List<String> v4Sido = new ArrayList<>();
		List<BigInteger[]> v6 = new ArrayList<>();
		List<String> v6Sido = new ArrayList<>();
		try (InputStream raw = new ClassPathResource(RESOURCE).getInputStream();
				BufferedReader reader = new BufferedReader(
						new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] parts = line.split("[|]", 4);
				if (parts[0].equals("4")) {
					v4.add(new long[] { Long.parseLong(parts[1]), Long.parseLong(parts[2]) });
					v4Sido.add(parts[3]);
				}
				else {
					v6.add(new BigInteger[] { new BigInteger(parts[1], 16), new BigInteger(parts[2], 16) });
					v6Sido.add(parts[3]);
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException("cannot read " + RESOURCE, e);
		}
		return new Table(v4.stream().mapToLong(r -> r[0]).toArray(), v4.stream().mapToLong(r -> r[1]).toArray(),
				v4Sido.toArray(String[]::new), v6.stream().map(r -> r[0]).toArray(BigInteger[]::new),
				v6.stream().map(r -> r[1]).toArray(BigInteger[]::new), v6Sido.toArray(String[]::new));
	}

}
