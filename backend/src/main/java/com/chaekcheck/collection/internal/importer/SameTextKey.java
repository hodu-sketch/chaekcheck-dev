package com.chaekcheck.collection.internal.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.catalog.normalize.Contributors.Contributor;
import com.chaekcheck.catalog.normalize.Contributors.Role;

/**
 * Step ④'s same-text key (cycle 12, ADR-0082, spec R2~R4): comparison title + volume marks + first author +
 * publisher, joined by {@link BookGrouper#SEP}; no key when a part is missing (not merged when unsure). Both the
 * {@code compact} and the {@code sql} grouping call it through {@link BookGrouper#sameTextKey}.
 * <ul>
 * <li>Comparison title: {@link CatalogText#mainTitleNorm} without the {@link #tails} at its end (R3-1).</li>
 * <li>Volume marks ({@link #volumeMarks}): from the whole title (NFKC, leading series name and tails removed) the
 * numbers in order ({@link #numbers}), 상·중·하 where they stand alone, and the edition words (R3-2), as
 * {@code n=1,2018|s=상|e=개정}.</li>
 * <li>First author: the first AUTHOR or UNKNOWN name that is not a placeholder; editors are not used (R2-4, R4).</li>
 * <li>Publisher: {@link CatalogText#publisherNorm}, none when a placeholder (R2).</li>
 * </ul>
 * The lists are constants: changing one needs a new import (ADR-0069).
 */
final class SameTextKey {

	/** Placeholder authors (by {@link Contributor#nameExact}); "." already has no exact name (R2-2 [확정]). */
	static final Set<String> PLACEHOLDER_AUTHORS = Set.of("기타", "unknown");

	/** Placeholder publishers (by {@link CatalogText#publisherNorm}); "." already has none (R2-2 [확정]). */
	static final Set<String> PLACEHOLDER_PUBLISHERS = Set.of("기타", "미상", "unknown");

	/** Edition words of the volume marks (R3-2 [확정]); the longest are found first. */
	static final List<String> EDITION_WORDS = List.of("개정", "증보", "전면개정", "특별판", "기념판", "리커버", "완결", "외전",
			"합본", "세트", "시즌");

	/**
	 * Notes taken off the title's end and out of the volume marks (R3-3), as written on the sites; compared normalized
	 * ({@link CatalogText#norm}). Usage notes and series names one publisher put on the same work [확정] (사용자
	 * 2026-10-12, cycle 12 step 12, candidates in plan-feedback "# 사이클 12").
	 */
	static final List<String> TAILS = List.of("(PC만 열람 가능)", "(스마트 패드 전용)", "(잠 못 이루는 밤을 위해)",
			"(한국인이 가장 좋아하는 단편소설)");

	/** A series name in front of the title, as in {@link CatalogText#mainTitleNorm}. */
	private static final Pattern LEADING_SERIES = Pattern.compile("^\\s*(?:〈[^〉]*〉|<[^>]*>|\\[[^\\]]*\\])\\s*");

	private static final Pattern NUMBER = Pattern.compile("[0-9]+");

	private static final Pattern LEADING_ZEROS = Pattern.compile("(?<![0-9])0+(?=[0-9])");

	/** Main title ends at the first " : " or " - ", as in {@link CatalogText#mainTitleNorm}. */
	private static final Pattern SUBTITLE_SEPARATOR = Pattern.compile("\\s+[:\\-]\\s+");

	/** A subtitle part this long or shorter (spaces not counted) gives all its numbers ("- 수학 1", "- 상"). */
	static final int SHORT_PART = 8;

	/**
	 * Numbers a long subtitle part gives (cycle 12 step 12 [확정] 사용자 2026-10-12, "권·판처럼 보이는 숫자만"): a
	 * number with a volume word after or before it, or a year. Numbers in the description ("10가지 법칙", "97세") are
	 * not volume marks.
	 */
	private static final Pattern VOLUME_NUMBER = Pattern.compile("(?<![0-9])([0-9]+)\\s*(?:권|부|편|집|기|탄|호|학년|학기|단계)"
			+ "|(?<![\\p{L}0-9])(?:vol|volume|part|book|tome|level|season|no|시즌|제)\\s*\\.?\\s*([0-9]+)"
			+ "|(?<![0-9])((?:19|20)[0-9]{2})(?![0-9])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/**
	 * A Roman numeral in capitals (I~XX, after NFKC also Ⅰ~Ⅻ), counted after a volume word or at the end of a part
	 * ("Volume II", "Tome I", "핌퍼넬 II"); not inside a sentence ("King George IV and …"). Cycle 12 [확정] 사용자
	 * 2026-10-12.
	 */
	private static final Pattern ROMAN = Pattern
		.compile("(?<![\\p{L}0-9])(X{1,2}(?:IX|IV|V?I{0,3})|IX|IV|V?I{1,3}|V)(?![\\p{L}0-9])");

	private static final Pattern ROMAN_AFTER_VOLUME_WORD = Pattern
		.compile("(?i)(?:vol|volume|tome|book|part|livre)\\.?\\s{0,3}$");

	/**
	 * Chinese numerals next to a volume word (卷, 第, 部, 編 and the Korean words) or at the end of a part ("作品卷一").
	 * Cycle 12 [확정] 사용자 2026-10-12 ("노출되는게 다르면 다른 책").
	 */
	private static final Pattern CHINESE_NUMBER = Pattern.compile("[一二三四五六七八九十百]+");

	/**
	 * 상·중·하 inside brackets, or after a space, "-" or "." and before the end, "권", a bracket or a subtitle
	 * separator; not inside a word ("하늘").
	 */
	private static final Pattern UPPER_MIDDLE_LOWER = Pattern
		.compile("(?<=[(\\[\\s\\-.·,])([상중하上中下])(?=권|[)\\]·,]|\\s*$|\\s+[:\\-]\\s)");

	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/** The edition words, longest first. */
	private static final List<String> EDITION_WORDS_LONGEST_FIRST = EDITION_WORDS.stream()
		.sorted(Comparator.comparingInt(String::length).reversed())
		.toList();

	/** The rules with {@link #TAILS}; after the patterns, which its constructor uses. */
	static final SameTextKey RULES = new SameTextKey(TAILS);

	/**
	 * The key of one group.
	 * @param key the key; null when a part is missing
	 * @param placeholderOnly no key only because the author or the publisher is a placeholder (R2): with the
	 * placeholder taken as a value there would be one
	 */
	record Key(String key, boolean placeholderOnly) {
	}

	private final List<String> tailNorms;

	private final List<Pattern> tailPatterns;

	/** For tests: other tails than {@link #TAILS}. */
	SameTextKey(List<String> tails) {
		this.tailNorms = tails.stream().map(CatalogText::norm).filter(t -> !t.isEmpty()).toList();
		this.tailPatterns = tails.stream().filter(t -> !CatalogText.norm(t).isEmpty()).map(SameTextKey::spaceInsensitive).toList();
	}

	Key of(String title, String authorRaw, String publisherRaw) {
		String comparisonTitle = comparisonTitle(title);
		List<Contributor> contributors = Contributors.parse(authorRaw);
		String author = firstAuthor(contributors);
		String publisher = CatalogText.publisherNorm(publisherRaw);
		boolean placeholderPublisher = publisher != null && PLACEHOLDER_PUBLISHERS.contains(publisher);
		if (comparisonTitle.isEmpty()) {
			return new Key(null, false);
		}
		if (author == null || placeholderPublisher) {
			boolean placeholderOnly = publisher != null && BookGrouper.firstAuthor(contributors) != null;
			return new Key(null, placeholderOnly);
		}
		if (publisher == null) {
			return new Key(null, false);
		}
		char sep = BookGrouper.SEP;
		return new Key(comparisonTitle + sep + volumeMarks(title) + sep + author + sep + publisher, false);
	}

	/**
	 * {@link CatalogText#mainTitleNorm} without the tails at its end, and without leading zeros of its numbers
	 * ("01" = "1", as in the volume marks); the main title when nothing would be left.
	 */
	String comparisonTitle(String title) {
		String main = LEADING_ZEROS.matcher(CatalogText.mainTitleNorm(title)).replaceAll("");
		String text = main;
		boolean cut = true;
		while (cut) {
			cut = false;
			for (String tail : tailNorms) {
				if (text.endsWith(tail) && text.length() > tail.length()) {
					text = text.substring(0, text.length() - tail.length());
					cut = true;
				}
			}
		}
		return text.isEmpty() ? main : text;
	}

	/** The volume marks of the whole title, e.g. {@code n=1,2018|s=상|e=개정,기념판}; empty parts stay. */
	String volumeMarks(String title) {
		if (title == null) {
			return "n=|s=|e=";
		}
		String text = LEADING_SERIES.matcher(Normalizer.normalize(title, Normalizer.Form.NFKC)).replaceFirst("");
		for (Pattern tail : tailPatterns) {
			text = tail.matcher(text).replaceAll(" ");
		}
		List<String> numbers = numbers(text);
		List<String> marks = new ArrayList<>();
		Matcher mark = UPPER_MIDDLE_LOWER.matcher(text);
		while (mark.find()) {
			marks.add(mark.group(1));
		}
		return "n=" + String.join(",", numbers) + "|s=" + String.join(",", marks) + "|e="
				+ String.join(",", editionWords(text));
	}

	/**
	 * The numbers of the volume marks in order (as integers, "01" = "1"): every number of the main title and of short
	 * subtitle parts ({@link #SHORT_PART}), only the volume numbers ({@link #VOLUME_NUMBER}) of longer parts, and in
	 * every part the Roman and Chinese numerals in a volume place.
	 */
	static List<String> numbers(String text) {
		List<String> numbers = new ArrayList<>();
		String[] parts = SUBTITLE_SEPARATOR.split(text, -1);
		for (int p = 0; p < parts.length; p++) {
			String part = parts[p];
			boolean all = p == 0 || WHITESPACE.matcher(part).replaceAll("").length() <= SHORT_PART;
			TreeMap<Integer, String> found = new TreeMap<>();
			if (all) {
				Matcher number = NUMBER.matcher(part);
				while (number.find()) {
					found.put(number.start(), integer(number.group()));
				}
			}
			else {
				Matcher volume = VOLUME_NUMBER.matcher(part);
				while (volume.find()) {
					for (int g = 1; g <= 3; g++) {
						if (volume.group(g) != null) {
							found.put(volume.start(g), integer(volume.group(g)));
						}
					}
				}
			}
			Matcher roman = ROMAN.matcher(part);
			while (roman.find()) {
				boolean afterVolumeWord = ROMAN_AFTER_VOLUME_WORD.matcher(part.substring(0, roman.start(1))).find();
				boolean atEnd = part.substring(roman.end(1)).matches("[\\s.,)\\]]*");
				if (afterVolumeWord || atEnd) {
					found.put(roman.start(1), Integer.toString(romanValue(roman.group(1).toUpperCase(Locale.ROOT))));
				}
			}
			Matcher chinese = CHINESE_NUMBER.matcher(part);
			while (chinese.find()) {
				String before = part.substring(0, chinese.start()).strip();
				String after = part.substring(chinese.end()).strip();
				if (before.endsWith("卷") || before.endsWith("巻") || before.endsWith("第") || before.endsWith("제")
						|| after.isEmpty() || "卷巻권部부編편".indexOf(after.charAt(0)) >= 0) {
					found.put(chinese.start(), Integer.toString(chineseValue(chinese.group())));
				}
			}
			numbers.addAll(found.values());
		}
		return numbers;
	}

	private static String integer(String digits) {
		String trimmed = digits.replaceFirst("^0+", "");
		return trimmed.isEmpty() ? "0" : trimmed;
	}

	static int romanValue(String roman) {
		int value = 0;
		for (int i = 0; i < roman.length(); i++) {
			int digit = switch (roman.charAt(i)) {
				case 'I' -> 1;
				case 'V' -> 5;
				default -> 10;
			};
			int next = i + 1 < roman.length() ? switch (roman.charAt(i + 1)) {
				case 'I' -> 1;
				case 'V' -> 5;
				default -> 10;
			} : 0;
			value += digit < next ? -digit : digit;
		}
		return value;
	}

	/** 一 = 1 … 十 = 10, 二十三 = 23, 百 = 100; enough for volume numbers. */
	static int chineseValue(String text) {
		String digits = "一二三四五六七八九";
		int total = 0;
		int current = 0;
		for (char c : text.toCharArray()) {
			int d = digits.indexOf(c);
			if (d >= 0) {
				current = d + 1;
			}
			else {
				int unit = c == '十' ? 10 : 100;
				total += (current == 0 ? 1 : current) * unit;
				current = 0;
			}
		}
		return total + current;
	}

	/** The edition words in the title (spaces ignored), longest first without reusing a found place; sorted. */
	private static Set<String> editionWords(String text) {
		StringBuilder rest = new StringBuilder(WHITESPACE.matcher(text.toLowerCase(Locale.ROOT)).replaceAll(""));
		Set<String> found = new TreeSet<>();
		for (String word : EDITION_WORDS_LONGEST_FIRST) {
			for (int at = rest.indexOf(word); at >= 0; at = rest.indexOf(word, at + word.length())) {
				found.add(word);
				for (int i = at; i < at + word.length(); i++) {
					rest.setCharAt(i, '\u0000');
				}
			}
		}
		return found;
	}

	/** First AUTHOR or UNKNOWN with an exact name that is not a placeholder; null when there is none (R2-4). */
	static String firstAuthor(List<Contributor> contributors) {
		return contributors.stream()
			.filter(c -> c.role() == Role.AUTHOR || c.role() == Role.UNKNOWN)
			.map(Contributor::nameExact)
			.filter(name -> !name.isEmpty() && !PLACEHOLDER_AUTHORS.contains(name))
			.findFirst()
			.orElse(null);
	}

	/** A tail as written, matched with any spaces between its characters and in any letter case. */
	private static Pattern spaceInsensitive(String tail) {
		String compact = WHITESPACE.matcher(Normalizer.normalize(tail, Normalizer.Form.NFKC)).replaceAll("");
		StringBuilder regex = new StringBuilder();
		compact.codePoints().forEach(c -> {
			if (!regex.isEmpty()) {
				regex.append("\\s*");
			}
			regex.append(Pattern.quote(Character.toString(c)));
		});
		return Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

}
