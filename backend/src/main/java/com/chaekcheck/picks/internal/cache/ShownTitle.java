package com.chaekcheck.picks.internal.cache;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The name a recommended book is shown by when it matched one of our books (ADR-0129): our book's title without the
 * edition marks, so every list shows one name for one book. Taken off [안: details]: a leading {@code [시리즈]}, the
 * last parentheses (edition or language: 「햄릿(Hamlet)」, 「오디세이아(고대 그리스어 완역본)」), a last word
 * "오리지널판", and the volume when it is the first ({@code 1}, {@code (상)}: 「토지. 1(1부 1권)」, 「위대한 유산(상)」).
 * Later volumes keep their number. When nothing would be left, the title stays as it is. The same reading tells
 * {@link BookChoice} which edition is the first volume.
 */
final class ShownTitle {

	private static final Pattern SERIES = Pattern.compile("^\\[[^\\]]*\\]\\s*");

	private static final Pattern LAST_PARENTHESES = Pattern.compile("\\s*\\(([^()]*)\\)$");

	private static final Pattern ORIGINAL_EDITION = Pattern.compile("\\s+오리지널판$");

	/** A volume in parentheses: 「(상)」, 「(2권)」. */
	private static final Pattern VOLUME_INSIDE = Pattern.compile("(?:제\\s*)?(\\d{1,2}|상|중|하)\\s*권?");

	/**
	 * A volume at the end after a separator: 「토지. 1」, 「탁류 3」, 「고향 상」, 「삼국지 2권」. Two digits at most, so
	 * 「화씨 451」 has none.
	 */
	private static final Pattern VOLUME_AT_END = Pattern
		.compile("(?:[.,]\\s*|\\s+)(?:제\\s*)?(\\d{1,2}|상|중|하)\\s*권?$|(?<=\\D)(\\d{1,2})권$");

	private static final Pattern TRAILING = Pattern.compile("[\\s.,:;·\\-]+$");

	/**
	 * @param base the title without the edition marks and without the volume
	 * @param volume the volume as written ({@code 1}, {@code 2}, {@code 상}), or null when the title has none
	 */
	record Reading(String base, String volume) {

		/** The first volume, or a book with no volume. */
		boolean firstVolume() {
			return volume == null || volume.equals("1") || volume.equals("상");
		}

	}

	private ShownTitle() {
	}

	/** The shown name of our book's {@code title}; null stays null. */
	static String of(String title) {
		if (title == null) {
			return null;
		}
		Reading reading = read(title);
		String shown = reading.firstVolume() ? reading.base() : reading.base() + " " + reading.volume();
		return shown.isBlank() ? title.strip() : shown;
	}

	static Reading read(String title) {
		String text = title.strip();
		String volume = null;
		Matcher series = SERIES.matcher(text);
		if (series.find() && series.end() < text.length()) {
			text = text.substring(series.end());
		}
		for (int round = 0; round < 2; round++) {
			Matcher original = ORIGINAL_EDITION.matcher(text);
			if (original.find()) {
				text = text.substring(0, original.start());
			}
			Matcher parentheses = LAST_PARENTHESES.matcher(text);
			if (parentheses.find() && parentheses.start() > 0) {
				Matcher inside = VOLUME_INSIDE.matcher(parentheses.group(1).strip());
				if (volume == null && inside.matches()) {
					volume = stripZeros(inside.group(1));
				}
				text = text.substring(0, parentheses.start());
			}
		}
		if (volume == null) {
			Matcher end = VOLUME_AT_END.matcher(text);
			if (end.find() && end.start() > 0) {
				volume = stripZeros(end.group(1) != null ? end.group(1) : end.group(2));
				text = text.substring(0, end.start());
			}
		}
		text = TRAILING.matcher(text).replaceAll("");
		return new Reading(text, volume);
	}

	private static String stripZeros(String volume) {
		return volume.chars().allMatch(Character::isDigit) ? Integer.toString(Integer.parseInt(volume)) : volume;
	}

}
