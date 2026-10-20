package com.chaekcheck.region.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SidoByIpTest {

	private final SidoByIp sidoByIp = new SidoByIp();

	@Test
	void findsTheSidoOfAnAddressInTheList() throws IOException {
		String[] busan = firstLine("4", "부산");
		long middle = (Long.parseLong(busan[1]) + Long.parseLong(busan[2])) / 2;
		assertThat(sidoByIp.sido(InetAddress.getByAddress(toFour(middle)).getHostAddress())).contains("부산");

		String[] seoul = firstLine("6", "서울");
		assertThat(sidoByIp.sido(InetAddress.getByAddress(toSixteen(new BigInteger(seoul[1], 16))).getHostAddress()))
			.contains("서울");
	}

	@Test
	void nothingForAnAddressOutsideKoreaOrNotAnAddress() {
		assertThat(sidoByIp.sido("8.8.8.8")).isEmpty();
		assertThat(sidoByIp.sido("127.0.0.1")).isEmpty();
		assertThat(sidoByIp.sido("localhost")).isEmpty();
		assertThat(sidoByIp.sido("")).isEmpty();
		assertThat(sidoByIp.sido(null)).isEmpty();
		assertThat(sidoByIp.sido("1.2.3.4, 5.6.7.8")).isEmpty();
	}

	private static byte[] toFour(long value) {
		return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16), (byte) (value >>> 8), (byte) value };
	}

	private static byte[] toSixteen(BigInteger value) {
		byte[] raw = value.toByteArray();
		byte[] bytes = new byte[16];
		int copy = Math.min(16, raw.length);
		System.arraycopy(raw, raw.length - copy, bytes, 16 - copy, copy);
		return bytes;
	}

	private static String[] firstLine(String version, String sido) throws IOException {
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(
				new GZIPInputStream(new ClassPathResource(SidoByIp.RESOURCE).getInputStream()),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				String[] parts = line.split("[|]");
				if (parts[0].equals(version) && parts[3].equals(sido)) {
					return parts;
				}
			}
		}
		throw new IllegalStateException("no " + sido + " line");
	}

}
