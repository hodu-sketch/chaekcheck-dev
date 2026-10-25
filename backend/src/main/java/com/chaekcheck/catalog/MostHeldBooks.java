package com.chaekcheck.catalog;

import java.util.List;

/**
 * Public API of the catalog module for the AI book search's {@code book-texts} import (user 2026-10-25: "보유량 많은 3만
 * 권"): the books held by the most libraries, the same choice as cycle 23's pool ({@code ORDER BY
 * cardinality(library_ids) DESC, id}), so that both sets are the same books.
 */
public interface MostHeldBooks {

	/** The {@code count} books held by the most libraries, most first, then by id. One query. */
	List<HeldBook> top(int count);

	/**
	 * Not meant for {@code equals}: {@code libraryIds} is an array.
	 * @param title {@code catalog.book.title}
	 * @param author {@code catalog.book.author_display}, or null when empty
	 * @param libraryIds {@code catalog.book.library_ids}
	 */
	record HeldBook(long bookId, String title, String author, long[] libraryIds) {

		public HeldBook {
			libraryIds = libraryIds.clone();
		}

		@Override
		public long[] libraryIds() {
			return libraryIds.clone();
		}

	}

}
