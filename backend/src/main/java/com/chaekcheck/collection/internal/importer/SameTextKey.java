package com.chaekcheck.collection.internal.importer;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
 * numbers in order, 상·중·하 where they stand alone, and the edition words (R3-2), as {@code n=1,2018|s=상|e=개정}.</li>
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
	 * Usage notes taken off the title's end and out of the volume marks (R3-3), as written on the sites; compared
	 * normalized ({@link CatalogText#norm}). TODO [확인 필요: Phase B 데이터 절차 → 사용자 확정] (cycle 12 handoff step
	 * 12): empty until the user settles the list.
	 */
	static final List<String> TAILS = List.of();

	static final SameTextKey RULES = new SameTextKey(TAILS);

	/** A series name in front of the title, as in {@link CatalogText#mainTitleNorm}. */
	private static final Pattern LEADING_SERIES = Pattern.compile("^\\s*(?:〈[^〉]*〉|<[^>]*>|\\[[^\\]]*\\])\\s*");

	private static final Pattern NUMBER = Pattern.compile("[0-9]+");

	private static final Pattern LEADING_ZEROS = Pattern.compile("(?<![0-9])0+(?=[0-9])");

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
		List<String> numbers = new ArrayList<>();
		Matcher number = NUMBER.matcher(text);
		while (number.find()) {
			String digits = number.group().replaceFirst("^0+", "");
			numbers.add(digits.isEmpty() ? "0" : digits);
		}
		List<String> marks = new ArrayList<>();
		Matcher mark = UPPER_MIDDLE_LOWER.matcher(text);
		while (mark.find()) {
			marks.add(mark.group(1));
		}
		return "n=" + String.join(",", numbers) + "|s=" + String.join(",", marks) + "|e="
				+ String.join(",", editionWords(text));
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
