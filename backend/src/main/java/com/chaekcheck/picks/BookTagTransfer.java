package com.chaekcheck.picks;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * The tag dictionary and the listed books' tags (cycle 23, ADR-0149 decision #9, R9): the {@code collection} import
 * carries what people confirmed (mode {@code tag-dictionary}) and what the crawl PC's Haiku batch chose (mode
 * {@code book-texts}, kind {@code tags}) through it. Only {@code collection} calls it.
 */
public interface BookTagTransfer {

	/**
	 * Adds or changes the given dictionary rows and makes every other known code inactive; no code is ever deleted,
	 * so tags already given keep their meaning.
	 * @return how many codes were active before but not in {@code rows}
	 */
	int upsertDictionary(Collection<DictionaryRow> rows);

	/**
	 * Replaces every tag of the book with {@code codes}. Codes that are not in the dictionary are skipped.
	 * @return how many of {@code codes} were skipped
	 */
	int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn);

	/**
	 * @param axis {@code mood}, {@code subject} or {@code audience}
	 * @param themaCode the Thema code the tag stands for, or null
	 */
	record DictionaryRow(String code, String axis, String name, String themaCode) {
	}

	/** One tag on a card. */
	record Tag(String code, String axis, String name) {
	}

	/**
	 * The tags a card shows (R9-5, AC-10): among the book's active tags, one mood and then one or two subjects, two
	 * or three in all; audience tags are not shown [안]. Without a mood the subjects alone (at most two). In each axis
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
