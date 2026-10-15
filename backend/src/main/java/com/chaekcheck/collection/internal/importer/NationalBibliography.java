package com.chaekcheck.collection.internal.importer;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.catalog.normalize.Isbn;

/**
 * ISBN lookup in the national bibliography general-book dump (lod.nl.go.kr {@code book_ttl_*.zip}, CC0 1.0, research
 * same-book-isbn 4.2). Fields used: {@code bibo:isbn}, {@code dcterms:title}, {@code dc:creator},
 * {@code dc:publisher}.
 * <p>
 * Since cycle 08 (plan ADR-0069) the records are kept as read in {@link NationalBibliographyStore}. While the files'
 * fingerprint (name, size and modified time of every {@code .ttl} file) is the stored one, the records are read from
 * the table and the TTL files are not opened; otherwise the files are read line by line (7.7GB, never held in memory)
 * and the table is rewritten on the way. Either way the keys are made from the raw values with the current
 * {@code catalog} normalize rules on every import, so a rule change needs no rewrite.
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

	/**
	 * ISBN-13 for each wanted key that exactly one record with one ISBN matches. Sets the report's
	 * {@code nationalBibliographyNormalize} (time spent making keys) and {@code nationalBibliographyReused}.
	 */
	/**
	 * {@link #lookup} from the stored records alone, without the TTL files (cycle 19: the TTL files were lost on
	 * 2026-10-15; the user chose to read the stored table, 2026-10-16). Stops when nothing is stored.
	 */
	static Map<String, String> lookupStored(Set<String> wantedKeys, NationalBibliographyStore store,
			ImportReport report) throws SQLException {
		if (store.fingerprint() == null) {
			throw new IllegalStateException("No stored national bibliography records; give ttl-path instead");
		}
		Matching matching = new Matching(wantedKeys, report);
		report.nationalBibliographyReused = true;
		store.forEach(matching::add);
		report.nationalBibliographyNormalize = Duration.ofNanos(matching.normalizeNanos);
		matching.found.values().removeIf(isbn -> isbn.equals(AMBIGUOUS));
		return matching.found;
	}

	static Map<String, String> lookup(Path ttlDirectory, Set<String> wantedKeys, NationalBibliographyStore store,
			Clock clock, ImportReport report) throws IOException, SQLException {
		List<Path> files;
		try (Stream<Path> list = Files.walk(ttlDirectory)) {
			files = list.filter(p -> p.getFileName().toString().endsWith(".ttl")).sorted().toList();
		}
		if (files.isEmpty()) {
			throw new IllegalArgumentException("No .ttl files under " + ttlDirectory);
		}
		Matching matching = new Matching(wantedKeys, report);
		String fingerprint = fingerprint(ttlDirectory, files);
		if (fingerprint.equals(store.fingerprint())) {
			report.nationalBibliographyReused = true;
			store.forEach(matching::add);
		}
		else {
			store.rewrite(fingerprint, clock.instant(), writer -> {
				for (Path file : files) {
					read(file, raw -> {
						writer.add(raw);
						matching.add(raw);
					});
				}
			});
		}
		report.nationalBibliographyNormalize = Duration.ofNanos(matching.normalizeNanos);
		matching.found.values().removeIf(isbn -> isbn.equals(AMBIGUOUS));
		return matching.found;
	}

	/** {@code name|size|modified} of every file, in path order, joined by {@code ;} (handoff step 3 [확정 형식]). */
	static String fingerprint(Path directory, List<Path> files) throws IOException {
		StringBuilder text = new StringBuilder();
		for (Path file : files) {
			if (!text.isEmpty()) {
				text.append(';');
			}
			text.append(directory.relativize(file).toString().replace('\\', '/'))
				.append('|')
				.append(Files.size(file))
				.append('|')
				.append(Files.getLastModifiedTime(file).toInstant());
		}
		return text.toString();
	}

	/** Records with an ISBN and a title, as read from one TTL file: one per ISBN. */
	private static void read(Path file, Consumer<NationalBibliographyStore.Raw> records) throws IOException {
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
					if (record.title != null) {
						for (String isbn : record.isbns) {
							records.accept(new NationalBibliographyStore.Raw(isbn, record.title, record.creator,
									record.publisher));
						}
					}
					record = null;
				}
			}
		}
	}

	/** Makes each record's key with the current rules and keeps the ISBNs of the wanted keys. */
	private static final class Matching {

		final Map<String, String> found = new HashMap<>();

		final Set<String> wantedKeys;

		final ImportReport report;

		long normalizeNanos;

		Matching(Set<String> wantedKeys, ImportReport report) {
			this.wantedKeys = wantedKeys;
			this.report = report;
		}

		void add(NationalBibliographyStore.Raw raw) {
			report.nationalBibliographyRecords++;
			long started = System.nanoTime();
			String key = keyOf(raw.title(), raw.creator(), raw.publisher());
			normalizeNanos += System.nanoTime() - started;
			if (key == null || !wantedKeys.contains(key)) {
				return;
			}
			// a record with several ISBNs comes as several rows: its key gets two ISBNs and becomes ambiguous
			String isbn = raw.isbn13();
			String previous = found.putIfAbsent(key, isbn);
			if (previous != null && !previous.equals(isbn) && !previous.equals(AMBIGUOUS)) {
				found.put(key, AMBIGUOUS);
				report.nationalBibliographyAmbiguous++;
			}
		}

	}

	/** Key of a bibliography record, with its noise removed first [안]: a leading bracket, a trailing {@code #}. */
	static String keyOf(String title, String creator, String publisher) {
		if (title == null || creator == null || publisher == null) {
			return null;
		}
		String mainTitle = CatalogText.mainTitleNorm(LEADING_BRACKET.matcher(title).replaceFirst(""));
		String author = BookGrouper.firstAuthor(Contributors.parse(creator.replace(';', '/')));
		String publisherNorm = CatalogText.publisherNorm(publisher.replaceAll("#+\\s*$", ""));
		return BookGrouper.key(mainTitle, author, publisherNorm);
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
