package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Public API of the catalog module for the 「나에게 맞는 책」 tab (cycle 23, ADR-0149): what a card shows, our book for a
 * holding a library site lists, and which of a set of books are editions of one work.
 */
public interface BookCards {

	/**
	 * The card fields of the given books, one row per book in no particular order. Ids without a book are left out
	 * (books an import removed); repeated ids give one row; nulls are skipped. One query for any number of ids.
	 */
	List<BookCard> cards(Collection<Long> bookIds);

	/**
	 * Our book of each holding, by {@code catalog.holding (library_id, item_key)}. Keys without a holding are left
	 * out; an empty input gives an empty map. One query for any number of keys.
	 */
	Map<HoldingKey, Long> byHoldingKeys(Collection<HoldingKey> keys);

	/**
	 * The representative edition of each given book among the given books (decision #6, the "같은 책" rule of cycle
	 * 21): books with the same {@code main_title_norm} that share at least one AUTHOR or UNKNOWN {@code name_exact}
	 * are one work, and editions joined through a shared name chain are one work too. The representative is the
	 * edition held by the most libraries, then the smallest id. Every known given id is a key (a book alone maps to
	 * itself); unknown ids are left out.
	 */
	Map<Long, Long> workKeys(Collection<Long> bookIds);

	/**
	 * One holding as a library site lists it.
	 * @param libraryId our library ({@code library.id})
	 * @param itemKey the site's own item number (KYOBO {@code brcd}, YES24 {@code goods_id})
	 */
	record HoldingKey(long libraryId, String itemKey) {
	}

	/**
	 * Not meant for {@code equals}: {@code libraryIds} is an array.
	 * @param title {@code catalog.book.title}
	 * @param author {@code catalog.book.author_display}, or null
	 * @param publisher {@code catalog.book.publisher}, or null
	 * @param publishedYear the year of {@code catalog.book.published_on}, the most common publish date of the book's
	 *     holdings (user 2026-10-24: "대표 출판일 그대로"), or null
	 * @param authorKey the {@code name_exact} of the first AUTHOR or UNKNOWN contributor, or null
	 * @param libraryIds {@code catalog.book.library_ids}
	 */
	record BookCard(long bookId, String title, String author, String publisher, Integer publishedYear,
			String authorKey, long[] libraryIds) {

		public BookCard {
			libraryIds = libraryIds.clone();
		}

		@Override
		public long[] libraryIds() {
			return libraryIds.clone();
		}

	}

}
