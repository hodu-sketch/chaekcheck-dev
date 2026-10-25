package com.chaekcheck.collection.internal.booktexts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.catalog.MostHeldBooks.HeldBook;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * The {@code book-texts} import mode (cycle 24 handoff step 4-3, ADR-0151): reads the file, then the listed books from
 * the database ({@link ListedBooks#loadFromDb()}: this command runs before any cache is filled) and the books held by
 * the most libraries ({@link MostHeldBooks}, user 2026-10-25: "보유량 많은 3만 권"), then replaces the AI search's books
 * with both (a book in both once, as listed: the shown name) and the file's search lines. Each book's library ids are
 * copied too. No listed book refuses the import and keeps the table as it is. Nothing else of the catalog is touched.
 */
public final class BookTextsImport {

	private static final Logger log = LoggerFactory.getLogger(BookTextsImport.class);

	private final ListedBooks listedBooks;

	private final MostHeldBooks mostHeldBooks;

	private final int topBooks;

	private final AiSearchBookTexts bookTexts;

	/** @param topBooks how many of the most held books to add (0: the listed books only) */
	public BookTextsImport(ListedBooks listedBooks, MostHeldBooks mostHeldBooks, int topBooks,
			AiSearchBookTexts bookTexts) {
		if (topBooks < 0) {
			throw new IllegalArgumentException("top books must be 0 or more: " + topBooks);
		}
		this.listedBooks = listedBooks;
		this.mostHeldBooks = mostHeldBooks;
		this.topBooks = topBooks;
		this.bookTexts = bookTexts;
	}

	/**
	 * @param fileRows the data rows of the file
	 * @param linesRead the {@code search_line} rows
	 * @param listed the listed books
	 * @param topOnly the most held books that are not listed
	 * @param result what was put in
	 */
	public record Report(int fileRows, int linesRead, java.util.Map<String, Integer> skippedKinds, int listed,
			int topOnly, Result result) {
	}

	/**
	 * @throws BookTextsFile.RefusedException when the file is refused
	 * @throws IllegalStateException when there is no listed book
	 */
	public Report run(Path file) {
		BookTextsFile.Contents contents = BookTextsFile.read(file);
		Snapshot snapshot = listedBooks.loadFromDb();
		if (snapshot.books().isEmpty()) {
			throw new IllegalStateException(
					"No recommended-list book is matched to our books in this database; the search books were kept");
		}
		List<Book> books = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (ListedBook book : snapshot.books()) {
			seen.add(book.bookId());
			books.add(new Book(book.bookId(), book.title(), book.author(), book.libraryIds()));
		}
		int topOnly = 0;
		for (HeldBook book : mostHeldBooks.top(topBooks)) {
			if (seen.add(book.bookId())) {
				books.add(new Book(book.bookId(), book.title(), book.author(), book.libraryIds()));
				topOnly++;
			}
		}
		Result result = bookTexts.replace(books, contents.lines());
		Report report = new Report(contents.rows(), contents.lines().size(), contents.skippedKinds(),
				snapshot.books().size(), topOnly, result);
		log.info("book-texts: file rows {}, search lines {}, other kinds skipped {}, listed books {}, most held {} "
				+ "({} not listed), stored {}, with line {}, lines for books not stored {}", report.fileRows(),
				report.linesRead(), report.skippedKinds(), report.listed(), topBooks, topOnly, result.stored(),
				result.withLine(), result.linesNotListed());
		return report;
	}

}
