package com.chaekcheck.aisearch.internal.flow;

/** Text with only whole characters: a surrogate without its pair becomes U+FFFD (cycle 24 step 8 review). */
public final class WholeText {

	private WholeText() {
	}

	public static String of(String text) {
		StringBuilder whole = null;
		for (int i = 0; i < text.length(); i++) {
			char c = text.charAt(i);
			boolean paired = Character.isHighSurrogate(c) && i + 1 < text.length()
					&& Character.isLowSurrogate(text.charAt(i + 1));
			if (paired) {
				if (whole != null) {
					whole.append(c).append(text.charAt(i + 1));
				}
				i++;
				continue;
			}
			boolean lone = Character.isSurrogate(c);
			if (lone && whole == null) {
				whole = new StringBuilder(text.length()).append(text, 0, i);
			}
			if (whole != null) {
				whole.append(lone ? PickStreamParser.REPLACEMENT : c);
			}
		}
		return whole == null ? text : whole.toString();
	}

}
