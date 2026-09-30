package com.chaekcheck.catalog.internal.importer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.chaekcheck.catalog.internal.normalize.CatalogText;
import com.chaekcheck.catalog.internal.normalize.Contributors;
import com.chaekcheck.catalog.internal.normalize.Isbn;

/**
 * ISBN lookup in the national bibliography general-book dump (lod.nl.go.kr {@code book_ttl_*.zip}, CC0 1.0, research
 * same-book-isbn 4.2). The TTL files are read line by line (7.7GB, never held in memory) and only records whose key
 * is wanted are kept. Fields used: {@code bibo:isbn}, {@code dcterms:title}, {@code dc:creator},
 * {@code dc:publisher}.
 * <p>
 * A key found in more than one record, or in a record with several ISBNs, is ambiguous and gets no ISBN (attach only
 * when exactly one record matches).
 */
final class NationalBibliography {

	private static final Pattern PREDICATE = Pattern.compile("^\\s+([a-zA-Z]+:[A-Za-z]+)\\s+(.*)$");

	private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

	/** Leading title words in brackets, e.g. "(장난감 피아노로 시작하는) 엄마표 피아노 레슨", "(The) tale". */
	private static final Pattern LEADING_BRACKET = Pattern.compile("^\\s*\\([^)]*\\)\\s*");

	private static final String AMBIGUOUS = "";

	private NationalBibliography() {
	}

	/** ISBN-13 for each wanted key that exactly one record with one ISBN matches. */
	static Map<String, String> lookup(Path ttlDirectory, Set<String> wantedKeys, ImportReport report)
			throws IOException {
		Map<String, String> found = new HashMap<>();
		if (wantedKeys.isEmpty()) {
			return found;
		}
		List<Path> files;
		try (Stream<Path> list = Files.walk(ttlDirectory)) {
			files = list.filter(p -> p.getFileName().toString().endsWith(".ttl")).sorted().toList();
		}
		if (files.isEmpty()) {
			throw new IllegalArgumentException("No .ttl files under " + ttlDirectory);
		}
		for (Path file : files) {
			read(file, wantedKeys, found, report);
		}
		found.values().removeIf(isbn -> isbn.equals(AMBIGUOUS));
		return found;
	}

	private static void read(Path file, Set<String> wantedKeys, Map<String, String> found, ImportReport report)
			throws IOException {
		Record record = null;
		String predicate = null;
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isEmpty() || line.startsWith("@prefix")) {
					continue;
				}
				if (!Character.isWhitespace(line.charAt(0))) {
					record = new Record();
					predicate = null;
					int space = line.indexOf(' ');
					line = space < 0 ? "" : " " + line.substring(space);
				}
				if (record == null) {
					continue;
				}
				Matcher m = PREDICATE.matcher(line);
				String values;
				if (m.matches()) {
					predicate = m.group(1);
					values = m.group(2);
				}
				else {
					values = line;
				}
				if (predicate != null) {
					record.add(predicate, values);
				}
				if (line.stripTrailing().endsWith(" .")) {
					record.finish(wantedKeys, found, report);
					record = null;
				}
			}
		}
	}

	/** Key of a bibliography record, with its noise removed first [안]: a leading bracket, a trailing {@code #}. */
	static String keyOf(String title, String creator, String publisher) {
		if (title == null || creator == null || publisher == null) {
			return null;
		}
		String mainTitle = CatalogText.mainTitleNorm(LEADING_BRACKET.matcher(title).replaceFirst(""));
		String author = BookGrouping.firstAuthor(Contributors.parse(creator.replace(';', '/')));
		String publisherNorm = CatalogText.publisherNorm(publisher.replaceAll("#+\\s*$", ""));
		return BookGrouping.key(mainTitle, author, publisherNorm);
	}

	private static final class Record {

		final Set<String> isbns = new LinkedHashSet<>();

		String title;

		String creator;

		String publisher;

		void add(String predicate, String values) {
			switch (predicate) {
				case "bibo:isbn" -> literals(values).forEach(raw -> {
					String isbn = Isbn.toIsbn13(raw);
					if (isbn != null) {
						isbns.add(isbn);
					}
				});
				case "dcterms:title" -> title = first(title, values);
				case "dc:creator" -> creator = first(creator, values);
				case "dc:publisher" -> publisher = first(publisher, values);
				default -> {
				}
			}
		}

		void finish(Set<String> wantedKeys, Map<String, String> found, ImportReport report) {
			if (isbns.isEmpty()) {
				return;
			}
			report.nationalBibliographyRecords++;
			String key = keyOf(title, creator, publisher);
			if (key == null || !wantedKeys.contains(key)) {
				return;
			}
			String isbn = isbns.size() == 1 ? isbns.iterator().next() : AMBIGUOUS;
			String previous = found.putIfAbsent(key, isbn);
			if (previous != null && !previous.equals(isbn) && !previous.equals(AMBIGUOUS)) {
				found.put(key, AMBIGUOUS);
				report.nationalBibliographyAmbiguous++;
			}
		}

		private static String first(String current, String values) {
			if (current != null) {
				return current;
			}
			List<String> literals = literals(values);
			return literals.isEmpty() ? null : literals.get(0);
		}

		private static List<String> literals(String values) {
			List<String> result = new ArrayList<>();
			Matcher m = LITERAL.matcher(values);
			while (m.find()) {
				result.add(m.group(1).replace("\\\"", "\"").replace("\\\\", "\\"));
			}
			return result;
		}

	}

}
