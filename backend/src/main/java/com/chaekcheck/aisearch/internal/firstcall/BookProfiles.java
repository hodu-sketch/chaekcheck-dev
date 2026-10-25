package com.chaekcheck.aisearch.internal.firstcall;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The detailed-dictionary profile of each book (27,227 books, Haiku 2026-10-25, chaekcheck-data
 * aisearch/book-profiles-detailed-2026-10-25.jsonl): the file {@code book-profiles.tsv} that
 * {@code scripts/aisearch/eval/export_first_call.py} writes next to the book vectors, one book a line: id, field,
 * subfield, fiction (1, 0 or empty), genres joined by {@code |}, country, audience, level, forms joined by {@code |}.
 * The ids are the catalog ids of the export the book vectors were made from.
 */
final class BookProfiles {

	static final String NAME = "book-profiles.tsv";

	record Profile(String field, String subfield, Boolean fiction, List<String> genre, String country, String audience,
			String level, List<String> form) {
	}

	private final Map<Long, Profile> byId;

	BookProfiles(Map<Long, Profile> byId) {
		this.byId = Map.copyOf(byId);
	}

	static BookProfiles read(Path file) throws IOException {
		Map<Long, Profile> byId = new HashMap<>();
		try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			String line;
			while ((line = reader.readLine()) != null) {
				if (line.isBlank()) {
					continue;
				}
				String[] cell = line.split("\t", -1);
				if (cell.length != 9) {
					throw new IOException("a book profile line of " + cell.length + " cells");
				}
				Boolean fiction = switch (cell[3]) {
					case "1" -> Boolean.TRUE;
					case "0" -> Boolean.FALSE;
					default -> null;
				};
				byId.put(Long.parseLong(cell[0]), new Profile(cell[1], cell[2], fiction, list(cell[4]), cell[5], cell[6],
						cell[7], list(cell[8])));
			}
		}
		return new BookProfiles(byId);
	}

	/** The book's profile, or null when it has none. */
	Profile of(long bookId) {
		return byId.get(bookId);
	}

	int count() {
		return byId.size();
	}

	private static List<String> list(String cell) {
		return cell.isEmpty() ? List.of() : Arrays.asList(cell.split("\\|"));
	}

}
