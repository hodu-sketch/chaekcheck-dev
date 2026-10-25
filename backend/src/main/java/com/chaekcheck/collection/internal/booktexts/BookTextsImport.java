package com.chaekcheck.collection.internal.booktexts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.catalog.MostHeldBooks.HeldBook;
import com.chaekcheck.foryou.ForYouTransfer;
import com.chaekcheck.picks.BookTagTransfer;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * The {@code book-texts} import mode (cycle 24 handoff step 4-3, ADR-0151): reads the file, then the listed books from
 * the database ({@link ListedBooks#loadFromDb()}: this command runs before any cache is filled) and the books held by
 * the most libraries ({@link MostHeldBooks}, user 2026-10-25: "보유량 많은 3만 권"), then replaces the AI search's books
 * with both (a book in both once, as listed: the shown name) and the file's search lines. Each book's library ids are
 * copied too. No listed book refuses the import and keeps the table as it is. Nothing else of the catalog is touched.
 * <p>
 * Cycle 23 step 3-3 (ADR-0149): the file's {@code for_you_blurb} and {@code cover_file} rows are upserted through
 * {@code foryou}'s {@link ForYouTransfer}, and each {@code tags} row replaces its book's tags through {@code picks}'
 * {@link BookTagTransfer} (codes not in the dictionary are skipped and counted). A file with rows of those kinds but no
 * search line leaves the AI search's books alone (cycle 24 would refuse it when lines are stored, or drop them); any
 * other file runs the cycle 24 step as before. Everything is written in one transaction.
 */
public final class BookTextsImport {

	private static final Logger log = LoggerFactory.getLogger(BookTextsImport.class);

	private final ListedBooks listedBooks;

	private final MostHeldBooks mostHeldBooks;

	private final int topBooks;

	private final AiSearchBookTexts bookTexts;

	private final ForYouTransfer forYou;

	private final BookTagTransfer bookTags;

	private final TransactionOperations transaction;

	/** @param topBooks how many of the most held books to add (0: the listed books only) */
	public BookTextsImport(ListedBooks listedBooks, MostHeldBooks mostHeldBooks, int topBooks,
			AiSearchBookTexts bookTexts, ForYouTransfer forYou, BookTagTransfer bookTags,
			TransactionOperations transaction) {
		if (topBooks < 0) {
			throw new IllegalArgumentException("top books must be 0 or more: " + topBooks);
		}
		this.listedBooks = listedBooks;
		this.mostHeldBooks = mostHeldBooks;
		this.topBooks = topBooks;
		this.bookTexts = bookTexts;
		this.forYou = forYou;
		this.bookTags = bookTags;
		this.transaction = transaction;
	}

	/**
	 * @param fileRows the data rows of the file
	 * @param linesRead the {@code search_line} rows
	 * @param result what was put in the AI search; null when the file had only cycle 23 kinds
	 * @param blurbs {@code for_you_blurb} rows written
	 * @param tagBooks books whose tags were replaced
	 * @param tagCodesSkipped codes of {@code tags} rows not in the dictionary
	 * @param covers {@code cover_file} rows written
	 * @param listed the listed books put in the AI search (0 when it was left alone)
	 * @param topOnly the most held books put in that are not listed
	 */
	public record Report(int fileRows, int linesRead, Map<String, Integer> skippedKinds, Result result, int blurbs,
			int tagBooks, int tagCodesSkipped, int covers, int listed, int topOnly) {
	}

	/** The books the AI search answers with: the listed books first, then the most held ones not listed. */
	private record SearchBooks(List<Book> books, int listed, int topOnly) {
	}

	/**
	 * @throws BookTextsFile.RefusedException when the file is refused
	 * @throws IllegalStateException when there is no listed book
	 */
	public Report run(Path file) {
		BookTextsFile.Contents contents = BookTextsFile.read(file);
		boolean replaceSearch = !contents.lines().isEmpty() || !contents.hasForYouKinds();
		SearchBooks search = replaceSearch ? searchBooks() : new SearchBooks(List.of(), 0, 0);
		Report report = transaction.execute(status -> {
			Result result = replaceSearch ? bookTexts.replace(search.books(), contents.lines()) : null;
			int blurbs = contents.blurbs().isEmpty() ? 0 : forYou.upsertBlurbs(contents.blurbs());
			int skippedCodes = 0;
			for (BookTextsFile.BookTags tags : contents.tags()) {
				skippedCodes += bookTags.replaceTags(tags.bookId(), tags.codes(), tags.madeOn());
			}
			int covers = contents.covers().isEmpty() ? 0 : forYou.upsertCovers(contents.covers());
			return new Report(contents.rows(), contents.lines().size(), contents.skippedKinds(), result, blurbs,
					contents.tags().size(), skippedCodes, covers, search.listed(), search.topOnly());
		});
		Result result = report.result();
		if (result != null) {
			log.info("book-texts: file rows {}, search lines {}, other kinds skipped {}, listed books {}, most held {} "
					+ "({} not listed), stored {}, with line {}, lines for books not stored {}", report.fileRows(),
					report.linesRead(), report.skippedKinds(), report.listed(), topBooks, report.topOnly(),
					result.stored(), result.withLine(), result.linesNotListed());
		}
		else {
			log.info("book-texts: file rows {}, no search line: the AI search's books were left as they are, other "
					+ "kinds skipped {}", report.fileRows(), report.skippedKinds());
		}
		if (contents.hasForYouKinds()) {
			log.info("book-texts for you: blurbs {}, books with tags replaced {}, tag codes not in the dictionary {}, "
					+ "covers {}", report.blurbs(), report.tagBooks(), report.tagCodesSkipped(), report.covers());
			if (report.tagCodesSkipped() > 0) {
				log.warn("book-texts: {} tag code(s) are not in the dictionary and were skipped; put the tag dictionary"
						+ " in first (mode tag-dictionary)", report.tagCodesSkipped());
			}
		}
		return report;
	}

	private SearchBooks searchBooks() {
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
		return new SearchBooks(books, snapshot.books().size(), topOnly);
	}

}
