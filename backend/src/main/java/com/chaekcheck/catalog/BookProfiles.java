package com.chaekcheck.catalog;

import java.util.List;
import java.util.Map;

/**
 * Public API of the catalog module: the book profiles the AI search's first call reads (cycle 26, ADR-0165), from
 * {@code catalog.book_profile}. The caller reads them once at start; the {@code book-profiles} import changes them in
 * another process, so a running server sees new profiles after a restart.
 */
public interface BookProfiles {

	/** Every profile by book id; equal texts share one string. */
	Map<Long, Profile> all();

	/** What the table holds now (its row count), to log next to what was read. */
	String version();

	/**
	 * The nine cells of the PC's {@code book-profiles} file without the id; an empty cell is null, an empty list is
	 * empty.
	 * @param fiction true, false or null when the PC did not say
	 */
	record Profile(String field, String subfield, Boolean fiction, List<String> genres, String country,
			String audience, String level, List<String> forms) {

		public Profile {
			genres = genres == null ? List.of() : List.copyOf(genres);
			forms = forms == null ? List.of() : List.copyOf(forms);
		}

	}

}
