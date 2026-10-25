package com.chaekcheck.collection.internal.booktexts;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Book;
import com.chaekcheck.aisearch.AiSearchBookTexts.Result;
import com.chaekcheck.foryou.ForYouTransfer;
import com.chaekcheck.picks.BookTagTransfer;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * The {@code book-texts} import mode (cycle 24 handoff step 4-3, ADR-0151): reads the file, then the listed books from
 * the database ({@link ListedBooks#loadFromDb()}: this command runs before any cache is filled), then replaces the AI
 * search's books with every listed book and the file's search lines. No listed book refuses the import and keeps the
 * table as it is. Nothing else of the catalog is touched.
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

	private final AiSearchBookTexts bookTexts;

	private final ForYouTransfer forYou;

	private final BookTagTransfer bookTags;

	private final TransactionOperations transaction;

	public BookTextsImport(ListedBooks listedBooks, AiSearchBookTexts bookTexts, ForYouTransfer forYou,
			BookTagTransfer bookTags, TransactionOperations transaction) {
		this.listedBooks = listedBooks;
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
	 */
	public record Report(int fileRows, int linesRead, Map<String, Integer> skippedKinds, Result result, int blurbs,
			int tagBooks, int tagCodesSkipped, int covers) {
	}

	/**
	 * @throws BookTextsFile.RefusedException when the file is refused
	 * @throws IllegalStateException when there is no listed book
	 */
	public Report run(Path file) {
		BookTextsFile.Contents contents = BookTextsFile.read(file);
		boolean searchBooks = !contents.lines().isEmpty() || !contents.hasForYouKinds();
		List<Book> books = searchBooks ? listedBooks() : List.of();
		Report report = transaction.execute(status -> {
			Result result = searchBooks ? bookTexts.replace(books, contents.lines()) : null;
			int blurbs = contents.blurbs().isEmpty() ? 0 : forYou.upsertBlurbs(contents.blurbs());
			int skippedCodes = 0;
			for (BookTextsFile.BookTags tags : contents.tags()) {
				skippedCodes += bookTags.replaceTags(tags.bookId(), tags.codes(), tags.madeOn());
			}
			int covers = contents.covers().isEmpty() ? 0 : forYou.upsertCovers(contents.covers());
			return new Report(contents.rows(), contents.lines().size(), contents.skippedKinds(), result, blurbs,
					contents.tags().size(), skippedCodes, covers);
		});
		Result result = report.result();
		if (result != null) {
			log.info("book-texts: file rows {}, search lines {}, other kinds skipped {}, listed books {}, with line {}, "
					+ "lines for books not listed {}", report.fileRows(), report.linesRead(), report.skippedKinds(),
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

	private List<Book> listedBooks() {
		Snapshot snapshot = listedBooks.loadFromDb();
		if (snapshot.books().isEmpty()) {
			throw new IllegalStateException(
					"No recommended-list book is matched to our books in this database; the search books were kept");
		}
		return snapshot.books().stream().map(book -> new Book(book.bookId(), book.title(), book.author())).toList();
	}

}
