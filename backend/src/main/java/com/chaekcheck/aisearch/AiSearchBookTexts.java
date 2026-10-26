package com.chaekcheck.aisearch;

import java.time.LocalDate;
import java.util.List;

/**
 * Public API of the aisearch module for the {@code collection} import's {@code book-texts} mode (cycle 24, ADR-0151):
 * replaces the books the AI search looks in and their search lines.
 */
public interface AiSearchBookTexts {

	/** The longest search line, in characters (design decision #8 [확정]); the database checks it too. */
	int LINE_MAX_CHARS = 300;

	/**
	 * Replaces every row of {@code aisearch.search_book} in one transaction: one row per given book, with its line
	 * when {@code lines} has one (else no line). Lines for books that are not in {@code books} are skipped and counted.
	 * @param books every book the AI search answers with: the listed books ({@code ListedBooks.loadFromDb()}) and the
	 *     books held by the most libraries ({@code catalog.MostHeldBooks}, user 2026-10-25), each book once; never
	 *     empty
	 * @param lines the search lines, each book at most once
	 * @throws IllegalArgumentException when {@code books} is empty, a book or a line's book comes twice, or a line is
	 *     blank or longer than {@value #LINE_MAX_CHARS} characters; nothing is changed
	 * @throws IllegalStateException when {@code lines} is empty but the stored rows have lines: a header-only or
	 *     wrong file would remove every line (the first import with titles and authors only still works); nothing is
	 *     changed
	 */
	Result replace(List<Book> books, List<Line> lines);

	/**
	 * Rewrites the search books' table without the space the replaced rows left (user 2026-10-27: after a replace the
	 * file held about 1.5 times the data, and a keyword on every book reads it all). Outside any transaction; it locks
	 * the table while it runs, so the AI search waits that long.
	 */
	default void compact() {
	}

	/**
	 * Not meant for {@code equals}: {@code libraryIds} is an array.
	 * @param bookId our book ({@code catalog.book.id})
	 * @param title the shown name, copied as it is now
	 * @param author our book's author text, or null
	 * @param libraryIds the libraries holding the book ({@code catalog.book.library_ids}), copied as they are now; the
	 *     keyword match counts the user's plan libraries among them
	 */
	record Book(long bookId, String title, String author, long[] libraryIds) {

		public Book {
			if (bookId < 1 || title == null || title.isBlank()) {
				throw new IllegalArgumentException("a book needs an id and a title: " + bookId);
			}
			libraryIds = libraryIds == null ? new long[0] : libraryIds.clone();
		}

		/** A book held by no library: stored, but never matched (tests). */
		public Book(long bookId, String title, String author) {
			this(bookId, title, author, new long[0]);
		}

		@Override
		public long[] libraryIds() {
			return libraryIds.clone();
		}

	}

	/**
	 * @param bookId the book the line is for
	 * @param source how it was made
	 * @param madeOn the day the PC batch made it
	 * @param text the line, 1 to {@value AiSearchBookTexts#LINE_MAX_CHARS} characters
	 */
	record Line(long bookId, Source source, LocalDate madeOn, String text) {

		public Line {
			if (bookId < 1) {
				throw new IllegalArgumentException("a line needs a book id of 1 or more: " + bookId);
			}
			if (source == null || madeOn == null || text == null) {
				throw new IllegalArgumentException("a line needs a source, a date and a text: " + bookId);
			}
			int length = text.codePointCount(0, text.length());
			if (text.isBlank() || length > LINE_MAX_CHARS) {
				throw new IllegalArgumentException(
						"a line must have 1 to " + LINE_MAX_CHARS + " characters, book " + bookId + " has " + length);
			}
		}

	}

	/** How a line was made (spec R10-3): from the library's book introduction, or from the bibliographic data only. */
	enum Source {

		INTRO("intro"), BIBLIOGRAPHIC("bibliographic");

		private final String code;

		Source(String code) {
			this.code = code;
		}

		/** The value in the TSV file and in {@code search_book.line_source}. */
		public String code() {
			return code;
		}

		/** @throws IllegalArgumentException for any other value */
		public static Source of(String code) {
			for (Source source : values()) {
				if (source.code.equals(code)) {
					return source;
				}
			}
			throw new IllegalArgumentException("unknown line source: " + code);
		}

	}

	/**
	 * @param stored rows written (every given book)
	 * @param withLine of those, rows with a line
	 * @param linesNotListed lines skipped because their book is not among the given books
	 */
	record Result(int stored, int withLine, int linesNotListed) {
	}

}
