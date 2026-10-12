package com.chaekcheck.picks.internal.fetch;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.core.io.ClassPathResource;

/**
 * The real answer of {@code saseoApi.do} for the two years up to 2026-10-12 ({@code fixtures/nlk/saseo-2y.xml}, fetched
 * once on 2026-10-12 with user approval, no key in it: 108 items in 12 months, each item in its own {@code <list>},
 * months latest first, all of them kept) and changed copies of it for the failure cases (plan cycle 14 step
 * 3). Tests never call the real API (C3).
 */
public final class NlkFixtures {

	private static final Pattern ITEM = Pattern.compile("<item>.*?</item>\\n?", Pattern.DOTALL);

	private NlkFixtures() {
	}

	public static String saseo2y() {
		try {
			return new ClassPathResource("fixtures/nlk/saseo-2y.xml").getContentAsString(StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

	public static byte[] bytes(String xml) {
		return xml.getBytes(StandardCharsets.UTF_8);
	}

	/** The items of an answer, as text. */
	public static List<String> items(String xml) {
		List<String> items = new ArrayList<>();
		Matcher m = ITEM.matcher(xml);
		while (m.find()) {
			items.add(m.group());
		}
		return items;
	}

	/** The answer with these items and {@code totalCount} = their number. */
	public static String withItems(List<String> items) {
		return withItems(items, items.size());
	}

	public static String withItems(List<String> items, int totalCount) {
		return """
				<?xml version="1.0" encoding="UTF-8"?>
				<channel>
				<totalCount>%d</totalCount>
				<list>
				%s</list>
				</channel>
				""".formatted(totalCount, String.join("", items));
	}

	/** One item element's text replaced. */
	public static String set(String item, String element, String value) {
		return item.replaceFirst("<" + element + ">[^<]*</" + element + ">",
				Matcher.quoteReplacement("<" + element + ">" + value + "</" + element + ">"));
	}

	/** A ticker that does not sleep: sleeping moves its clock. */
	public static final class FakeTicker implements RequestPacer.Ticker {

		private long now = 1_000_000_000L;

		private final List<Duration> sleeps = new ArrayList<>();

		@Override
		public long nanoTime() {
			return now;
		}

		@Override
		public void sleep(Duration duration) {
			sleeps.add(duration);
			now += duration.toNanos();
		}

		public void advance(Duration duration) {
			now += duration.toNanos();
		}

		public List<Duration> sleeps() {
			return List.copyOf(sleeps);
		}

		public void clear() {
			sleeps.clear();
		}

	}

}
