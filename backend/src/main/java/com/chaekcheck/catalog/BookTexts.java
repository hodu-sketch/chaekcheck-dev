package com.chaekcheck.catalog;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Public API of the catalog module: the book descriptions other modules show (cycle 26, ADR-0161): the 책첵 summary,
 * the cover of an edition and the tags. Read from the database on every call, one query each; ids without a row are left
 * out, repeated ids give one entry and nulls are skipped.
 */
public interface BookTexts {

	/** The 책첵 summary of each given book that has one, never cut (1 to 300 characters). */
	Map<Long, String> summaries(Collection<Long> bookIds);

	/** The cover address ({@link CoverUrls#of}) of each given edition that has a cover of its own. */
	Map<Long, String> coverUrls(Collection<Long> bookIds);

	/**
	 * The cover address of each given book for a recommended list row (ADR-0164 ① and ②): the edition's own cover, else
	 * the cover of another edition of the same work, the latest published first, then the one held by the most
	 * libraries. Editions are one work when their {@code main_title_norm} is the same and they share an AUTHOR or
	 * UNKNOWN {@code name_exact} (the {@link BookCards#workKeys} rule). Books with neither are left out; the caller
	 * then shows the list's own picture (ADR-0164 ③).
	 */
	Map<Long, String> listCoverUrls(Collection<Long> bookIds);

	/** The active-dictionary tags of each given book that has one, in code order. */
	Map<Long, List<Tag>> tags(Collection<Long> bookIds);

	/**
	 * One tag of a book.
	 * @param axis {@code mood}, {@code subject} or {@code audience}
	 */
	record Tag(String code, String axis, String name) {
	}

	/**
	 * The tags a card shows (cycle 23 R9-5, AC-10): among the book's active tags, one mood and then one or two subjects,
	 * two or three in all; audience tags are not shown. Without a mood the subjects alone (at most two). In each axis
	 * the dictionary's code order.
	 */
	static List<Tag> forCard(Collection<Tag> tags) {
		List<Tag> sorted = tags.stream().sorted(Comparator.comparing(Tag::code)).toList();
		List<Tag> card = new ArrayList<>(3);
		sorted.stream().filter(tag -> tag.axis().equals("mood")).findFirst().ifPresent(card::add);
		sorted.stream().filter(tag -> tag.axis().equals("subject")).limit(2).forEach(card::add);
		return List.copyOf(card);
	}

}
