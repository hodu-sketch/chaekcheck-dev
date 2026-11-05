package com.chaekcheck.picks;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Set;

import com.chaekcheck.catalog.BookTexts;

/**
 * Public API of the picks module for the AI book search (cycle 24, ADR-0150): every book on the recommended lists that
 * is matched to one of our books, once. Rows without our book are left out; a book on several lists is one row with
 * every list key, in the order of {@code PicksCache.LISTS} and then each list's own order. The title is the shown
 * name (ADR-0129) and the author is our book's ({@code catalog.book.author_display}, user 2026-10-24), so both are the
 * same as on the book page.
 * <p>
 * TODO(24-F1): the cache keeps no author, so the author is read from catalog once per cache refresh. When the picks
 * cache or the book matching changes (cycle 23 merge included), let the cache carry it (dev docs/cycles/plan-feedback.md
 * "후속 작업 24-F1").
 */
public interface ListedBooks {

	/**
	 * The listed books of the server's shared cache. Built again only after the cache was read again (one catalog
	 * query per cache refresh for the authors; after a failed read, at most one try a minute); otherwise no database
	 * call. Empty until the cache is filled.
	 */
	Snapshot current();

	/**
	 * The listed books read from the database now, the same way the cache reads them, without the cache: for the
	 * import commands, which run before the cache is filled (cycle 24 design review #6).
	 * @throws ListsNotReadException when a list that has stored rows came back empty (its read failed), so that no
	 *     caller takes a set missing whole lists; a failing database read otherwise throws as it is
	 */
	Snapshot loadFromDb();

	/** @param builtAt when this set was made */
	record Snapshot(Instant builtAt, List<ListedBook> books) {

		public Snapshot {
			books = List.copyOf(books);
		}

	}

	/**
	 * Not meant for {@code equals}: {@code libraryIds} is an array.
	 * @param bookId our book ({@code catalog.book.id})
	 * @param title the shown name (ADR-0129)
	 * @param author our book's author text, or null
	 * @param coverPath the cover's CloudFront address (cycle 26, ADR-0164), or null
	 * @param libraryIds {@code catalog.book.library_ids} of the book
	 * @param listKeys the lists the book is on ({@code librarian}, {@code leedongjin}, …)
	 * @param aiSummary our book's 책첵 summary ({@code catalog.book_summary}, cycle 26; the one S14 shows), or null
	 * @param tags the tags a card shows ({@code catalog.BookTexts.forCard}, active codes only, cycle 23 R9-5), or null
	 *     when the book has none or they could not be read
	 * @param recommendedMonth the month of the librarian list's pick, for the book window's year line (cycle 23), or
	 *     null when the book is not on that list
	 * @param yearBook the KAIST list's year, for the book window's year line (cycle 23), or null when not on it
	 * @param fieldGroup one of {@link FieldGroups#ALL}, from the first list's field ({@link FieldGroups#of}), for
	 *     mixing the fields while a browser has seen few books (user 2026-10-25); null when no list gives one
	 */
	record ListedBook(long bookId, String title, String author, String coverPath, long[] libraryIds,
			List<String> listKeys, String aiSummary, List<BookTexts.Tag> tags, YearMonth recommendedMonth,
			Integer yearBook, String fieldGroup) {

		public ListedBook {
			libraryIds = libraryIds.clone();
			listKeys = List.copyOf(listKeys);
			tags = tags == null || tags.isEmpty() ? null : List.copyOf(tags);
		}

		/** A book without tags or year line. */
		public ListedBook(long bookId, String title, String author, String coverPath, long[] libraryIds,
				List<String> listKeys, String aiSummary) {
			this(bookId, title, author, coverPath, libraryIds, listKeys, aiSummary, null, null, null, null);
		}

		/** A book without an AI summary or tags. */
		public ListedBook(long bookId, String title, String author, String coverPath, long[] libraryIds,
				List<String> listKeys) {
			this(bookId, title, author, coverPath, libraryIds, listKeys, null, null, null, null, null);
		}

		@Override
		public long[] libraryIds() {
			return libraryIds.clone();
		}

		/** How many of the given libraries hold the book, without copying the ids. */
		public int countHeldBy(Set<Long> libraries) {
			int count = 0;
			for (long libraryId : libraryIds) {
				if (libraries.contains(libraryId)) {
					count++;
				}
			}
			return count;
		}

	}

	/** A list with stored rows could not be read; nothing was returned. */
	final class ListsNotReadException extends RuntimeException {

		public ListsNotReadException(List<String> lists) {
			super("Recommended lists could not be read: " + lists);
		}

	}

}
