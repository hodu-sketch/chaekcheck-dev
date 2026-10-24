package com.chaekcheck.collection.internal.booktexts;

import java.nio.file.Path;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * The {@code book-texts} import mode (cycle 24 handoff step 4-3, ADR-0151): reads the file, then the listed books from
 * the database ({@link ListedBooks#loadFromDb()}: this command runs before any cache is filled), then replaces the AI
 * search's books with every listed book and the file's search lines. No listed book refuses the import and keeps the
 * table as it is. Nothing else of the catalog is touched.
 */
public final class BookTextsImport {

	private static final Logger log = LoggerFactory.getLogger(BookTextsImport.class);

	private final ListedBooks listedBooks;

	private final AiSearchBookTexts bookTexts;

	public BookTextsImport(ListedBooks listedBooks, AiSearchBookTexts bookTexts) {
		this.listedBooks = listedBooks;
		this.bookTexts = bookTexts;
	}

	/**
	 * @param fileRows the data rows of the file
	 * @param linesRead the {@code search_line} rows
	 * @param result what was put in
	 */
	public record Report(int fileRows, int linesRead, java.util.Map<String, Integer> skippedKinds, Result result) {
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
		List<Book> books = snapshot.books()
			.stream()
			.map(book -> new Book(book.bookId(), book.title(), book.author()))
			.toList();
		Result result = bookTexts.replace(books, contents.lines());
		Report report = new Report(contents.rows(), contents.lines().size(), contents.skippedKinds(), result);
		log.info("book-texts: file rows {}, search lines {}, other kinds skipped {}, listed books {}, with line {}, "
				+ "lines for books not listed {}", report.fileRows(), report.linesRead(), report.skippedKinds(),
				result.stored(), result.withLine(), result.linesNotListed());
		return report;
	}

}
