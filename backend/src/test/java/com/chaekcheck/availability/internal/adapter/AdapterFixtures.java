package com.chaekcheck.availability.internal.adapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.util.unit.DataSize;

import com.chaekcheck.availability.internal.config.AvailabilityProperties;

/**
 * Real pages saved on 2026-09-27 with descriptions, covers and scripts removed (src/test/resources/fixtures).
 */
public final class AdapterFixtures {

	static final AvailabilityProperties.Caches CACHES = new AvailabilityProperties.Caches(1000, 1000,
			Duration.ofMinutes(5));

	/** Cycle 20 settings as in application.yml: 8 parse slots / 2s, status 1s · 10s · 30s · 10s · 60s · 200, 1m. */
	public static final AvailabilityProperties.ParseSlots PARSE_SLOTS = new AvailabilityProperties.ParseSlots(8,
			Duration.ofSeconds(2));

	public static final AvailabilityProperties.LookupStatus LOOKUP_STATUS = new AvailabilityProperties.LookupStatus(
			Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(30), Duration.ofSeconds(10),
			Duration.ofSeconds(60), 200);

	public static final AvailabilityProperties.OpsSummary OPS_SUMMARY = new AvailabilityProperties.OpsSummary(
			Duration.ofMinutes(1));

	/** KYOBO record count 50 matches the cycle 01 fixtures (saved with recordCount=50). */
	public static final AvailabilityProperties PROPERTIES = new AvailabilityProperties(Duration.ofMinutes(3), CACHES, 1,
			Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(8),
			new AvailabilityProperties.Batch(20, 5, Duration.ofSeconds(8), Duration.ofSeconds(3), 50),
			new AvailabilityProperties.Detail(Duration.ofSeconds(30)), new AvailabilityProperties.Kyobo(50),
			new AvailabilityProperties.Yes24("title"), "chaekcheck/test", null, Duration.ofSeconds(15),
			DataSize.ofMegabytes(4), 20, PARSE_SLOTS, LOOKUP_STATUS, OPS_SUMMARY);

	/** Same as {@link #PROPERTIES} with KYOBO record count 10 (cycle 02 fixtures). */
	public static final AvailabilityProperties PROPERTIES_10 = new AvailabilityProperties(Duration.ofMinutes(3), CACHES,
			1, Duration.ofSeconds(1), Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(8),
			PROPERTIES.batch(), PROPERTIES.detail(), new AvailabilityProperties.Kyobo(10),
			new AvailabilityProperties.Yes24("title"), "chaekcheck/test", null, Duration.ofSeconds(15),
			DataSize.ofMegabytes(4), 20, PARSE_SLOTS, LOOKUP_STATUS, OPS_SUMMARY);

	private AdapterFixtures() {
	}

	/** @param name e.g. {@code kyobo-v1/love-p1.html}; UTF-8 fixtures only */
	public static String html(String name) {
		return new String(bytes(name), StandardCharsets.UTF_8);
	}

	/** The fixture's raw bytes (EUC-KR fixtures are stored as the site sent them). */
	public static byte[] bytes(String name) {
		try {
			return new ClassPathResource("fixtures/" + name).getContentAsByteArray();
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static byte[] utf8(String html) {
		return html.getBytes(StandardCharsets.UTF_8);
	}

}
