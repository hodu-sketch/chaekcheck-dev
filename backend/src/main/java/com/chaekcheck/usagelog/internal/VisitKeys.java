package com.chaekcheck.usagelog.internal;

/**
 * The visit key a browser tab sends (ADR-0134): 16 to 64 ASCII letters or digits, as the CHECK constraints of
 * {@code book_view}, {@code library_click} and {@code screen_event} allow. Anything else becomes null and is never a
 * reason to reject a request or a usage report. The web sends {@code crypto.randomUUID()} without hyphens (32).
 */
public final class VisitKeys {

	/** Header of the book page GET. */
	public static final String HEADER = "X-Visit-Key";

	/** Body field of a usage report. */
	public static final String FIELD = "visitKey";

	static final int MIN_LENGTH = 16;

	static final int MAX_LENGTH = 64;

	private VisitKeys() {
	}

	/** The value when it has the right shape, else null. Surrounding blanks are not accepted. */
	public static String normalize(String value) {
		if (value == null || value.length() < MIN_LENGTH || value.length() > MAX_LENGTH) {
			return null;
		}
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			boolean ok = (c >= '0' && c <= '9') || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z');
			if (!ok) {
				return null;
			}
		}
		return value;
	}

}
