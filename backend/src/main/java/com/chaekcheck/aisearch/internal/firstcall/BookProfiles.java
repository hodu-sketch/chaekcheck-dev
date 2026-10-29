package com.chaekcheck.aisearch.internal.firstcall;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The detailed-dictionary profile of each book, kept in memory for the first call: read once at start from
 * {@code catalog.book_profile} ({@link #of}, cycle 26, ADR-0165), which the {@code book-profiles} import fills. An
 * empty cell is {@code ""} here. The ids are the catalog ids of the export the book vectors were made from.
 */
final class BookProfiles {

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
		// the table keeps an empty cell as NULL where the file has "" (the shelves compare these words; NPE in
		// production after the step 12 import, 2026-10-29)
		profiles.forEach((id, p) -> byId.put(id, new Profile(text(p.field()), text(p.subfield()), p.fiction(),
				p.genres(), text(p.country()), text(p.audience()), text(p.level()), p.forms())));
		return new BookProfiles(byId);
	}

	private static String text(String cell) {
		return cell == null ? "" : cell;
	}

	/** The book's profile, or null when it has none. */
	Profile of(long bookId) {
		return byId.get(bookId);
	}

	int count() {
		return byId.size();
	}

}
