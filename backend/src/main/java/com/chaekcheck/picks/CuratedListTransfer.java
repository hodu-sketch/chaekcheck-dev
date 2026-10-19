package com.chaekcheck.picks;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.chaekcheck.catalog.BookLookup;

/**
 * Lee Dong-jin's picks, the SNU 100, KAIST's list (user 2026-10-19) and the Bokklubben World Library
 * ({@code nobel}, user 2026-10-20) travel with the catalog import
 * (ADR-0123, ADR-0124): the PC reads the list files the collector keeps ({@code scripts/crawl/lists/}), matches each row's fixed edition to a book of the catalog
 * being exported, and puts the result in the export's notes as {@code listPart}; the production apply, the PC's finish
 * and the in-place import write it with {@link #replace} after the catalog swap, in a short transaction of their own.
 * The librarian picks are not part of it: the server still fetches them (ADR-0122).
 * <p>
 * The import calls {@link #check} before it reads the collected copy (a list file that is wrong stops it before any
 * long work), {@link #read} once the new catalog is prepared, and {@link #replace} after the swap. The import mode
 * {@code lists} (user 2026-10-19) does only {@link #check}, {@link #read} with the live catalog and {@link #replace}.
 */
public interface CuratedListTransfer {

	/** The files every export needs in the lists folder (ADR-0123): without them the export stops. */
	List<String> LIST_FILES = List.of("fields.tsv", "leedongjin.tsv", "snu100.tsv", "kaist.tsv", "nobel.tsv");

	/**
	 * Reads and checks the list files without looking at any book.
	 * @throws ListFileException naming the file, the line and what is wrong
	 */
	void check(Path listsDir);

	/**
	 * Checks the files again, matches each row to one of our books in {@code lookup} (handoff step 4-4: the fixed
	 * edition's ISBN, then its title, first author and publisher, the choice among several by the held count; a row
	 * without a fixed edition by its own ISBN, then title and author) and puts the rows and their covers into one JSON
	 * text.
	 * @param lookup the catalog the export will carry ({@code CatalogLoad.preparedLookup()}), or the swapped-in one
	 * @throws ListFileException as {@link #check}
	 */
	ListPart read(Path listsDir, BookLookup lookup);

	/**
	 * Puts a {@link ListPart#json()} in place of the stored Lee Dong-jin, SNU, KAIST and nobel rows and their fields,
	 * in one
	 * transaction under the lists' locks; adds the covers not stored yet and deletes the covers no list uses. The
	 * librarian rows are not touched.
	 * @throws IllegalArgumentException when the text is not a list part
	 */
	Replaced replace(String listPartJson);

	/**
	 * @param json the {@code listPart} the export keeps in its notes
	 * @param counts per list ({@code leedongjin}, {@code snu100}, {@code kaist}, {@code nobel})
	 * @param warnings lines the import log keeps: rows not checked on YouTube for over 30 days, cover files missing
	 */
	record ListPart(String json, Map<String, Counts> counts, List<String> warnings) {
	}

	/** @param matched rows matched to one of our books; the rest show the list's title and a title-text cover */
	record Counts(int rows, int matched, int unmatched) {
	}

	/** What {@link #replace} wrote. */
	record Replaced(int fields, int books, int coversAdded, int coversDeleted) {
	}

	/** A list file that cannot be used, with where. */
	final class ListFileException extends IllegalArgumentException {

		private final String file;

		private final int line;

		public ListFileException(String file, int line, String message) {
			super(file + (line > 0 ? " line " + line : "") + ": " + message);
			this.file = file;
			this.line = line;
		}

		public String file() {
			return file;
		}

		/** 1 for the header line; 0 when the whole file is meant. */
		public int line() {
			return line;
		}

	}

}
