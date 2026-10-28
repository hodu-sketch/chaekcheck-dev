package com.chaekcheck.foryou.internal.listed;

import java.time.YearMonth;
import java.util.List;

import com.chaekcheck.catalog.BookTexts.Tag;
import com.chaekcheck.picks.ListedBooks;

/** The books on the recommended lists, for the base list's order (R2-4), the card footers (R3-2) and S14. */
public interface ListedSource {

	ListedSource NONE = List::of;

	List<ListedRef> listed();

	/** The server's shared copy of picks' listed books ({@code ListedBooks.current()}, no database call per request). */
	static ListedSource of(ListedBooks books) {
		return () -> books.current()
			.books()
			.stream()
			.map(book -> new ListedRef(book.bookId(), book.listKeys(), book.libraryIds(),
					book.tags() == null ? List.of() : book.tags(), book.recommendedMonth(),
					book.yearBook(), book.fieldGroup()))
			.toList();
	}

	/**
	 * @param listKeys the lists the book is on ({@code librarian}, {@code kaist}, …)
	 * @param libraryIds {@code catalog.book.library_ids} of the book
	 * @param tags the tags a card shows ({@code catalog.BookTexts.forCard}), empty when none
	 * @param recommendedMonth the librarian list's month for S14's year line, or null
	 * @param yearBook the KAIST list's year for S14's year line, or null
	 * @param fieldGroup the book's field group ({@code picks.FieldGroups}), or null
	 */
	record ListedRef(long bookId, List<String> listKeys, long[] libraryIds, List<Tag> tags, YearMonth recommendedMonth,
			Integer yearBook, String fieldGroup) {

		/** A book without S14's year line or field group. */
		public ListedRef(long bookId, List<String> listKeys, long[] libraryIds, List<Tag> tags) {
			this(bookId, listKeys, libraryIds, tags, null, null, null);
		}

	}

}
