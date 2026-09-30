package com.chaekcheck.availability.internal.title;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Title comparison key (ADR-0018, spec 5.8): rows whose keys are equal are the same title when the web groups a
 * batch. Rows are never merged. Only the title is used this cycle.
 * <ol>
 * <li>Unicode NFC (Hangul typed as separate jamo becomes syllables)</li>
 * <li>all whitespace removed ("채식 주의자" = "채식주의자")</li>
 * <li>lower case</li>
 * <li>punctuation and symbols removed</li>
 * </ol>
 * Bracketed subtitles, "큰글자책" and volume numbers are kept as they are: the fixtures of the six screen kinds
 * showed them written differently per site, and merging them could join different volumes. Decided later with
 * real data [확인 필요].
 */
public final class BookKeyNormalizer {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

	/** Punctuation (\p{P}) and symbols (\p{S}): quotes, brackets, colons, middle dots, ... */
	private static final Pattern PUNCTUATION = Pattern.compile("[\\p{P}\\p{S}]");

	public String key(String title) {
		if (title == null) {
			return "";
		}
		String key = Normalizer.normalize(title, Normalizer.Form.NFC);
		key = WHITESPACE.matcher(key).replaceAll("");
		key = key.toLowerCase(Locale.ROOT);
		return PUNCTUATION.matcher(key).replaceAll("");
	}

}
