package com.chaekcheck.catalog.normalize;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Normalized values of titles, publishers and search words (research search-and-normalization 4장 [안]). The
 * importer and the search use this same class (ADR-0031), so a search word is cleaned exactly like the stored
 * value. The original text is kept next to every value, so a rule change needs a new import, not a new crawl.
 */
public final class CatalogText {

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** A series name in front of the title: {@code 〈…〉}, {@code <…>} or {@code […]} (4.2). */
	private static final Pattern LEADING_SERIES = Pattern.compile("^\\s*(?:〈[^〉]*〉|<[^>]*>|\\[[^\\]]*\\])\\s*");

	/** Main title ends at the first " : " or " - " (ISBD separators, 4.2). */
	private static final Pattern SUBTITLE_SEPARATOR = Pattern.compile("\\s+[:\\-]\\s+");

	/** Company words removed from publishers (4.3); after NFKC "㈜" is "(주)". */
	private static final Pattern PUBLISHER_COMPANY = Pattern
		.compile("\\(주\\)|주식회사|도서출판|(?<![\\p{L}\\p{N}])(?:inc|ltd|co)(?![\\p{L}\\p{N}])\\.?");

	private static final Pattern PUBLISHER_PUNCTUATION = Pattern.compile("[\\s.,]+");

	private CatalogText() {
	}

	/** NFKC, then English lower case, then all whitespace removed. Null or blank gives an empty string. */
	public static String norm(String raw) {
		if (raw == null) {
			return "";
		}
		return WHITESPACE.matcher(nfkc(raw).toLowerCase(Locale.ROOT)).replaceAll("");
	}

	/** Search words of one field: split on whitespace, each normalized; empty words dropped. */
	public static List<String> words(String raw) {
		if (raw == null) {
			return List.of();
		}
		return Arrays.stream(WHITESPACE.split(nfkc(raw).strip())).map(CatalogText::norm).filter(w -> !w.isEmpty()).toList();
	}

	public static String titleNorm(String title) {
		return norm(title);
	}

	/**
	 * Main title: a leading series name removed, then the part before the first " : " or " - ". Volume numbers and
	 * trailing brackets stay ({@code 이기적 유전자(40주년 기념판)}). Falls back to the whole title when nothing is
	 * left.
	 */
	public static String mainTitleNorm(String title) {
		if (title == null) {
			return "";
		}
		String text = nfkc(title);
		String withoutSeries = LEADING_SERIES.matcher(text).replaceFirst("");
		String main = SUBTITLE_SEPARATOR.split(withoutSeries, 2)[0];
		String normalized = norm(main);
		return normalized.isEmpty() ? norm(text) : normalized;
	}

	/**
	 * Publisher: NFKC, company words ({@code (주)}, {@code 주식회사}, {@code 도서출판}, Inc/Ltd/Co) removed, spaces,
	 * periods and commas removed, lower case. Null when nothing is left.
	 */
	public static String publisherNorm(String publisher) {
		if (publisher == null) {
			return null;
		}
		String text = nfkc(publisher).toLowerCase(Locale.ROOT);
		text = PUBLISHER_COMPANY.matcher(text).replaceAll(" ");
		text = PUBLISHER_PUNCTUATION.matcher(text).replaceAll("");
		return text.isEmpty() ? null : text;
	}

	/** Name for exact author match: normalized, periods removed too ("로버트 M. 새폴스키" = "로버트M새폴스키"). */
	public static String nameExact(String name) {
		return norm(name).replace(".", "");
	}

	static String nfkc(String raw) {
		return Normalizer.normalize(raw, Normalizer.Form.NFKC);
	}

}
