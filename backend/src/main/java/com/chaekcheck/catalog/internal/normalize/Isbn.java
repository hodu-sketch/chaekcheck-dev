package com.chaekcheck.catalog.internal.normalize;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** ISBN rules for grouping (research same-book-isbn 3절, 4.2). */
public final class Isbn {

	/** Kyobo-style code: 480 + nine digits (t) + a check digit. */
	private static final Pattern KYOBO_CODE = Pattern.compile("^480(\\d{9})(\\d)$");

	private static final Pattern NON_DIGIT_X = Pattern.compile("[^0-9Xx]");

	private Isbn() {
	}

	/** Outcome of reading a Kyobo-style code. */
	public enum KyoboResult {

		/** an ISBN was computed */
		ISBN,
		/** not a {@code 480} + 10 digit code (other platforms, {@code 480D…}) */
		NOT_KYOBO_CODE,
		/** a {@code 480} code that is not a Korean ISBN, e.g. {@code 48090…} [확인 필요] */
		NOT_KOREAN,
		/** the Kyobo check digit does not match [확인 필요] */
		BAD_CHECK_DIGIT

	}

	public record Kyobo(KyoboResult result, String isbn13) {
	}

	/**
	 * Paper-book ISBN from a Kyobo-style code: t starting with 89 gives 978+t, 11 gives 979+t, and the ISBN-13 check
	 * digit is computed again [확정 for 978-89 and 979-11]. The Kyobo check digit is always the one of 978+t.
	 */
	public static Kyobo fromKyoboCode(String code) {
		if (code == null) {
			return new Kyobo(KyoboResult.NOT_KYOBO_CODE, null);
		}
		Matcher m = KYOBO_CODE.matcher(code.strip());
		if (!m.matches()) {
			return new Kyobo(KyoboResult.NOT_KYOBO_CODE, null);
		}
		String t = m.group(1);
		if (checkDigit13("978" + t) != m.group(2).charAt(0) - '0') {
			return new Kyobo(KyoboResult.BAD_CHECK_DIGIT, null);
		}
		String prefix = t.startsWith("89") ? "978" : t.startsWith("11") ? "979" : null;
		if (prefix == null) {
			return new Kyobo(KyoboResult.NOT_KOREAN, null);
		}
		String body = prefix + t;
		return new Kyobo(KyoboResult.ISBN, body + checkDigit13(body));
	}

	/**
	 * ISBN-13 from national bibliography text: ISBN-10 or ISBN-13 with hyphens, spaces or an additional code glued
	 * to it ({@code 89-20-90635-193350}, {@code 7500004087 (34)}). Null when no valid ISBN is found.
	 */
	public static String toIsbn13(String raw) {
		if (raw == null) {
			return null;
		}
		String text = raw.strip();
		int bracket = text.indexOf('(');
		if (bracket >= 0) {
			text = text.substring(0, bracket);
		}
		String digits = NON_DIGIT_X.matcher(text).replaceAll("").toUpperCase();
		if (digits.length() >= 13 && (digits.startsWith("978") || digits.startsWith("979"))) {
			String isbn = digits.substring(0, 13);
			if (isbn.chars().allMatch(Character::isDigit) && checkDigit13(isbn.substring(0, 12)) == isbn.charAt(12) - '0') {
				return isbn;
			}
		}
		if (digits.length() >= 10) {
			String isbn10 = digits.substring(0, 10);
			if (isbn10.substring(0, 9).chars().allMatch(Character::isDigit) && validIsbn10(isbn10)) {
				String body = "978" + isbn10.substring(0, 9);
				return body + checkDigit13(body);
			}
		}
		return null;
	}

	static int checkDigit13(String twelveDigits) {
		int sum = 0;
		for (int i = 0; i < 12; i++) {
			int d = twelveDigits.charAt(i) - '0';
			sum += (i % 2 == 0) ? d : d * 3;
		}
		return (10 - sum % 10) % 10;
	}

	private static boolean validIsbn10(String isbn10) {
		int sum = 0;
		for (int i = 0; i < 10; i++) {
			char c = isbn10.charAt(i);
			int d = (c == 'X') ? 10 : c - '0';
			if (d > 9 && i < 9) {
				return false;
			}
			sum += d * (10 - i);
		}
		return sum % 11 == 0;
	}

}
