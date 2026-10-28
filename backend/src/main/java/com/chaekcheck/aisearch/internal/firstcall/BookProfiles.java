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
 * The detailed-dictionary profile of each book, kept in memory for the first call. Since cycle 26 (ADR-0165) read once
 * at start from {@code catalog.book_profile} ({@link #of}, put in by the {@code book-profiles} import); while that
 * table is empty, from the file {@code book-profiles.tsv} next to the book vectors as before ({@link #read}): one book
 * a line, id, field, subfield, fiction (1, 0 or empty), genres joined by {@code |}, country, audience, level, forms
 * joined by {@code |}. The ids are the catalog ids of the export the book vectors were made from.
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

	/** From the catalog's profiles. */
	static BookProfiles of(Map<Long, com.chaekcheck.catalog.BookProfiles.Profile> profiles) {
		Map<Long, Profile> byId = new HashMap<>(profiles.size() * 2);
		profiles.forEach((id, p) -> byId.put(id, new Profile(p.field(), p.subfield(), p.fiction(), p.genres(),
				p.country(), p.audience(), p.level(), p.forms())));
		return new BookProfiles(byId);
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
