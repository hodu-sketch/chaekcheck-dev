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

	private final com.chaekcheck.aisearch.internal.match.SearchBooks searchBooks;

	TestSearchBooks(JdbcClient jdbc, BookTextsTransfer transfer,
			com.chaekcheck.aisearch.internal.match.SearchBooks searchBooks) {
		this.jdbc = jdbc;
		this.transfer = transfer;
		this.searchBooks = searchBooks;
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
		return replace(books, lines, List.of());
	}

	/** As {@link #replace(List, List)}, with the summaries and search terms of the two layers (2026-10-30). */
	public SearchResult replace(List<Book> books, List<Line> lines, List<BookTextsTransfer.Written> written) {
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
		SearchResult result = writeRows(books, lines, written);
		// as the import's reload request does (cycle 29): the application's books are read again only when asked
		searchBooks.reload();
		return result;
	}

	/**
	 * The rows {@link BookTextsTransfer#replaceSearchBooks} writes, written in place with DELETE + INSERT. Since cycle 29
	 * the transfer swaps in a new table, whose lock lasts until the commit; a test's transaction never commits, so the
	 * flow's keyword search on its own connection would wait for it for ever. The swap itself is tested in
	 * {@code catalog.AiSearchIndexTests}.
	 */
	private SearchResult writeRows(List<Book> books, List<Line> lines, List<BookTextsTransfer.Written> written) {
		java.util.Map<Long, Line> lineByBook = new HashMap<>();
		lines.forEach(line -> lineByBook.put(line.bookId(), line));
		java.util.Map<Long, BookTextsTransfer.Written> writtenByBook = new HashMap<>();
		written.forEach(row -> writtenByBook.put(row.bookId(), row));
		jdbc.sql("DELETE FROM catalog.book_search").update();
		int withLine = 0;
		for (Book book : books) {
			Line line = lineByBook.get(book.bookId());
			BookTextsTransfer.Written words = writtenByBook.get(book.bookId());
			withLine += line == null ? 0 : 1;
			java.util.List<String> parts = new java.util.ArrayList<>();
			for (String part : new String[] { book.title(), book.author(), line == null ? null : line.text(),
					words == null ? null : words.terms() }) {
				if (part != null && !part.isBlank()) {
					parts.add(String.join(" ", part.strip().split("\s+")));
				}
			}
			jdbc.sql("""
					INSERT INTO catalog.book_search (book_id, line, line_source, line_made_on, search_text, library_ids,
					  summary, terms, written_on, keyword_text)
					VALUES (:id, :line, :source, :lineOn, :text, CAST(:libraries AS integer[]), :summary, :terms,
					  :writtenOn, :keyword)""")
				.param("id", book.bookId())
				.param("line", line == null ? null : line.text())
				.param("source", line == null ? null : line.source().code())
				.param("lineOn", line == null ? null : line.madeOn())
				.param("text", com.chaekcheck.catalog.SearchText.ofBook(book.title(), book.author(),
						line == null ? null : line.text()))
				.param("libraries", java.util.Arrays.stream(book.libraryIds()).boxed().toArray(Long[]::new))
				.param("summary", words == null ? null : words.summary())
				.param("terms", words == null ? null : words.terms())
				.param("writtenOn", words == null ? null : words.madeOn())
				.param("keyword", String.join(" ", parts))
				.update();
		}
		return new SearchResult(books.size(), withLine, 0, 0);
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
