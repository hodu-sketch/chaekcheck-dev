package com.chaekcheck.aisearch.internal.firstcall;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The shelves of the detailed dictionary (plan taxonomy2, the same words as {@link BookProfiles}) a question asks for
 * and the ones it asks to leave out. Empty or null: the question does not say.
 * @param fiction true: a novel or story; false: not one (also "소설 말고"); null: not said
 */
public record Shelves(List<String> field, List<String> subfield, Boolean fiction, List<String> genre, String country,
		String audience, String level, List<String> form, List<String> excludeField, List<String> excludeSubfield,
		List<String> excludeGenre) {

	public Shelves {
		field = List.copyOf(field);
		subfield = List.copyOf(subfield);
		genre = List.copyOf(genre);
		form = List.copyOf(form);
		excludeField = List.copyOf(excludeField);
		excludeSubfield = List.copyOf(excludeSubfield);
		excludeGenre = List.copyOf(excludeGenre);
		country = country == null ? "" : country;
		audience = audience == null ? "" : audience;
		level = level == null ? "" : level;
	}

	/** The shelves the book's profile shares with the question (0 to 8), as {@code cands_facet.matches}. */
	int matches(BookProfiles.Profile book) {
		if (book == null) {
			return 0;
		}
		int m = 0;
		if (field.contains(book.field())) {
			m++;
		}
		if (subfield.contains(book.subfield())) {
			m++;
		}
		if (fiction != null && fiction.equals(book.fiction())) {
			m++;
		}
		if (shares(genre, book.genre())) {
			m++;
		}
		if (!country.isEmpty() && country.equals(book.country())) {
			m++;
		}
		if (!audience.isEmpty() && audience.equals(book.audience())) {
			m++;
		}
		if (!level.isEmpty() && level.equals(book.level())) {
			m++;
		}
		if (shares(form, book.form())) {
			m++;
		}
		return m;
	}

	/**
	 * Whether the question rules the book out: a novel when it wants none, or a shelf it asks to leave out (as
	 * {@code cands_facet.excluded}).
	 */
	boolean excludes(BookProfiles.Profile book) {
		if (book == null) {
			return false;
		}
		if (Boolean.FALSE.equals(fiction) && Boolean.TRUE.equals(book.fiction())) {
			return true;
		}
		return excludeField.contains(book.field()) || excludeSubfield.contains(book.subfield())
				|| shares(excludeGenre, book.genre());
	}

	/**
	 * The dialogue state (plan 3, M4): the earlier question's shelves with every shelf this question gives put over
	 * them; the left-out shelves of both.
	 */
	Shelves over(Shelves earlier) {
		return new Shelves(field.isEmpty() ? earlier.field : field, subfield.isEmpty() ? earlier.subfield : subfield,
				fiction == null ? earlier.fiction : fiction, genre.isEmpty() ? earlier.genre : genre,
				country.isEmpty() ? earlier.country : country, audience.isEmpty() ? earlier.audience : audience,
				level.isEmpty() ? earlier.level : level, form.isEmpty() ? earlier.form : form,
				union(earlier.excludeField, excludeField), union(earlier.excludeSubfield, excludeSubfield),
				union(earlier.excludeGenre, excludeGenre));
	}

	/**
	 * A moved sub-topic ("식단 말고 운동 쪽은?"): this question's shelves, the earlier big shelf when this one gives none;
	 * the left-out shelves of both.
	 */
	Shelves keepingFieldOf(Shelves earlier) {
		return new Shelves(field.isEmpty() ? earlier.field : field, subfield, fiction, genre, country, audience, level,
				form, union(earlier.excludeField, excludeField), union(earlier.excludeSubfield, excludeSubfield),
				union(earlier.excludeGenre, excludeGenre));
	}

	/**
	 * The new-topic rule (M4): this question alone names a big shelf and none of it is the earlier question's, so the
	 * earlier question is dropped.
	 */
	boolean newTopicAfter(Shelves earlier) {
		return !field.isEmpty() && field.stream().noneMatch(earlier.field::contains);
	}

	private static boolean shares(Collection<String> a, Collection<String> b) {
		return a.stream().anyMatch(b::contains);
	}

	private static List<String> union(List<String> a, List<String> b) {
		LinkedHashSet<String> all = new LinkedHashSet<>(a);
		all.addAll(b);
		return new ArrayList<>(all);
	}

}
