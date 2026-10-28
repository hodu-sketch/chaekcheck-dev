package com.chaekcheck.aisearch;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookTexts;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.BookTextsTransfer.Line;
import com.chaekcheck.catalog.BookTextsTransfer.SearchResult;

/**
 * The AI search's books for tests (cycle 26: the search books are {@code catalog.book_search}, whose title, author
 * and library ids come from {@code catalog.book}): writes each book's {@code catalog.book} row, then the search books
 * through {@link BookTextsTransfer#replaceSearchBooks}, as the {@code book-texts} import does.
 */
@Component
public class TestSearchBooks {

	private final JdbcClient jdbc;

	private final BookTextsTransfer transfer;

	TestSearchBooks(JdbcClient jdbc, BookTextsTransfer transfer) {
		this.jdbc = jdbc;
		this.transfer = transfer;
	}

	/**
	 * A search book as the tests give it.
	 * @param libraryIds the libraries holding it
	 */
	public record Book(long bookId, String title, String author, long[] libraryIds) {

		public Book(long bookId, String title, String author) {
			this(bookId, title, author, new long[0]);
		}

	}

	/** Writes the books' catalog rows (replacing rows of the same id) and replaces the search books with them. */
	public SearchResult replace(List<Book> books, List<Line> lines) {
		for (Book book : books) {
			jdbc.sql("""
					INSERT INTO catalog.book (id, title, author_display, title_norm, main_title_norm, content_type,
					  library_ids)
					VALUES (:id, :title, :author, :title, :title, 'EBOOK', CAST(:libraries AS bigint[]))
					ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, author_display = EXCLUDED.author_display,
					  library_ids = EXCLUDED.library_ids""")
				.param("id", book.bookId())
				.param("title", book.title())
				.param("author", book.author())
				.param("libraries", java.util.Arrays.stream(book.libraryIds()).boxed().toArray(Long[]::new))
				.update();
		}
		return transfer.replaceSearchBooks(books.stream().map(Book::bookId).toList(), lines);
	}

	/** {@link BookTexts} for a flow test: covers and summaries from the given reads, no tags or list covers. */
	public static BookTexts texts(Function<Collection<Long>, Map<Long, String>> covers,
			Function<Collection<Long>, Map<Long, String>> summaries) {
		return new BookTexts() {

			@Override
			public Map<Long, String> summaries(Collection<Long> bookIds) {
				return summaries.apply(bookIds);
			}

			@Override
			public Map<Long, String> coverUrls(Collection<Long> bookIds) {
				return covers.apply(bookIds);
			}

			@Override
			public Map<Long, String> listCoverUrls(Collection<Long> bookIds) {
				return new HashMap<>();
			}

			@Override
			public Map<Long, List<Tag>> tags(Collection<Long> bookIds) {
				return new HashMap<>();
			}

		};
	}

}
