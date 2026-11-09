package com.chaekcheck.collection.internal.booktexts;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionOperations;

import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.BookTextsTransfer.SearchResult;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.catalog.MostHeldBooks.HeldBook;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.ListedBooks.ListedBook;
import com.chaekcheck.picks.ListedBooks.Snapshot;

/**
 * The {@code book-texts} import mode (cycle 24 handoff step 4-3, ADR-0151): reads the file, then the listed books from
 * the database ({@link ListedBooks#loadFromDb()}: this command runs before any cache is filled) and the books held by
 * the most libraries ({@link MostHeldBooks}, user 2026-10-25: "보유량 많은 3만 권"), then replaces the AI search's books
 * with both (a book in both once) and the file's search lines. A most held book named by a {@code search_exclude} row
 * is left out (2026-10-25, books without a usable intro); a listed book never is, and the place is not filled with the
 * next most held book. No listed book refuses the import and keeps the table as it is.
 * <p>
 * Cycle 23 step 3-3 (ADR-0149): the file's {@code for_you_blurb} and {@code cover_file} rows are upserted, and each
 * {@code tags} row replaces its book's tags (codes not in the dictionary are skipped and counted). A file with rows of
 * those kinds but no search line leaves the AI search's books alone; any other file replaces them. Everything is
 * written in one transaction.
 * <p>
 * Since cycle 26 (ADR-0161, ADR-0162) every kind goes into {@code catalog} through {@link BookTextsTransfer}: the
 * blurbs are the 책첵 summaries, and the search books' titles, authors and library ids are read from
 * {@code catalog.book}. The file's first row names the catalog export its book ids are from; a file for another export
 * is refused before anything is written, unless {@code force}.
 */
public final class BookTextsImport {

	private static final Logger log = LoggerFactory.getLogger(BookTextsImport.class);

	private final ListedBooks listedBooks;

	private final MostHeldBooks mostHeldBooks;

	private final int topBooks;

	private final BookTextsTransfer transfer;

	/** Asks the running backends to read the AI search's books again (cycle 29, ADR-0174). */
	private final Runnable searchReplaced;

	private final Supplier<Optional<UUID>> latestExportId;

	private final TransactionOperations transaction;

	/**
	 * @param topBooks how many of the most held books to add (0: the listed books only)
	 * @param latestExportId this database's latest succeeded catalog import's export id ({@link ExportIds#latest})
	 */
	public BookTextsImport(ListedBooks listedBooks, MostHeldBooks mostHeldBooks, int topBooks,
			BookTextsTransfer transfer, Runnable searchReplaced, Supplier<Optional<UUID>> latestExportId,
			TransactionOperations transaction) {
		if (topBooks < 0) {
			throw new IllegalArgumentException("top books must be 0 or more: " + topBooks);
		}
		this.listedBooks = listedBooks;
		this.mostHeldBooks = mostHeldBooks;
		this.topBooks = topBooks;
		this.transfer = transfer;
		this.searchReplaced = searchReplaced;
		this.latestExportId = latestExportId;
		this.transaction = transaction;
	}

	/**
	 * @param fileRows the data rows of the file
	 * @param linesRead the {@code search_line} rows
	 * @param result what was put in the AI search; null when the file had only cycle 23 kinds
	 * @param blurbs {@code for_you_blurb} rows written (책첵 summaries)
	 * @param tagBooks books whose tags were replaced
	 * @param tagCodesSkipped codes of {@code tags} rows not in the dictionary
	 * @param covers {@code cover_file} rows written
	 * @param listed the listed books put in the AI search (0 when it was left alone)
	 * @param topOnly the most held books put in that are not listed
	 * @param excluded the most held books left out by {@code search_exclude} rows
	 * @param forced whether the file's export id differed and {@code force} let it through
	 */
	public record Report(int fileRows, int linesRead, Map<String, Integer> skippedKinds, SearchResult result,
			int blurbs, int tagBooks, int tagCodesSkipped, int covers, int listed, int topOnly, int excluded,
			boolean forced) {
	}

	/** The books the AI search answers with: the listed books first, then the most held ones not listed. */
	private record SearchBooks(List<Long> bookIds, int listed, int topOnly, int excluded) {
	}

	/**
	 * @throws BookTextsFile.RefusedException when the file is refused (rows, or another export without {@code force})
	 * @throws IllegalStateException when there is no listed book
	 */
	public Report run(Path file, boolean force) {
		BookTextsFile.Contents contents = BookTextsFile.read(file);
		boolean forced = ExportIds.check(latestExportId.get(), contents.exportId(), force, "book-texts");
		boolean replaceSearch = !contents.lines().isEmpty() || !contents.hasForYouKinds();
		SearchBooks search = replaceSearch ? searchBooks(contents.excluded()) : new SearchBooks(List.of(), 0, 0, 0);
		Report report = transaction.execute(status -> {
			int blurbs = contents.blurbs().isEmpty() ? 0 : transfer.upsertSummaries(contents.blurbs());
			int skippedCodes = 0;
			for (BookTextsFile.BookTags tags : contents.tags()) {
				skippedCodes += transfer.replaceTags(tags.bookId(), tags.codes(), tags.madeOn());
			}
			int covers = contents.covers().isEmpty() ? 0 : transfer.upsertCovers(contents.covers());
			// last (cycle 29): the swap locks catalog.book_search until the commit, so nothing else runs after it
			SearchResult result = replaceSearch
					? transfer.replaceSearchBooks(search.bookIds(), contents.lines(), contents.written()) : null;
			return new Report(contents.rows(), contents.lines().size(), contents.skippedKinds(), result, blurbs,
					contents.tags().size(), skippedCodes, covers, search.listed(), search.topOnly(), search.excluded(),
					forced);
		});
		SearchResult result = report.result();
		if (result != null) {
			// the new table was swapped in (no VACUUM FULL since cycle 29); the backends read it again
			searchReplaced.run();
			log.info("book-texts: file rows {}, search lines {}, other kinds skipped {}, listed books {}, most held {} "
					+ "({} not listed, {} excluded), stored {}, with line {}, not in the catalog {}, lines for books not "
					+ "stored {}", report.fileRows(), report.linesRead(), report.skippedKinds(), report.listed(), topBooks,
					report.topOnly(), report.excluded(), result.stored(), result.withLine(), result.notInCatalog(),
					result.linesNotListed());
		}
		else {
			log.info("book-texts: file rows {}, no search line: the AI search's books were left as they are, other "
					+ "kinds skipped {}", report.fileRows(), report.skippedKinds());
		}
		if (contents.hasForYouKinds()) {
			log.info("book-texts summaries and covers: summaries {}, books with tags replaced {}, tag codes not in the "
					+ "dictionary {}, covers {}", report.blurbs(), report.tagBooks(), report.tagCodesSkipped(),
					report.covers());
			if (report.tagCodesSkipped() > 0) {
				log.warn("book-texts: {} tag code(s) are not in the dictionary and were skipped; put the tag dictionary"
						+ " in first (mode tag-dictionary)", report.tagCodesSkipped());
			}
		}
		return report;
	}

	private SearchBooks searchBooks(Set<Long> excludedIds) {
		Snapshot snapshot = listedBooks.loadFromDb();
		if (snapshot.books().isEmpty()) {
			throw new IllegalStateException(
					"No recommended-list book is matched to our books in this database; the search books were kept");
		}
		List<Long> books = new ArrayList<>();
		Set<Long> seen = new HashSet<>();
		for (ListedBook book : snapshot.books()) {
			if (seen.add(book.bookId())) {
				books.add(book.bookId());
			}
		}
		int topOnly = 0;
		int excluded = 0;
		for (HeldBook book : mostHeldBooks.top(topBooks)) {
			if (!seen.contains(book.bookId()) && excludedIds.contains(book.bookId())) {
				excluded++;
			}
			else if (seen.add(book.bookId())) {
				books.add(book.bookId());
				topOnly++;
			}
		}
		return new SearchBooks(books, snapshot.books().size(), topOnly, excluded);
	}

}
