package com.chaekcheck.collection.internal.foryou;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.ToLongFunction;
import java.util.stream.Stream;

import com.chaekcheck.foryou.ForYouTransfer.Neighbours;

/**
 * The {@code neighbours} file (cycle 23 step 3-3, ADR-0149, design-delta "넣는 명령" [안]): UTF-8, first row
 * {@code # export_id=<uuid>} (the {@code catalog_import.export_id} of the catalog whose book ids the crawl PC used),
 * then one row per book {@code book_id\tn1,n2,…} with the {@link Neighbours} rules (nearest first, 1 to 20, not the
 * book itself, each once, ids up to 2,147,483,647) and each book once. About 1.3 million rows: the file is checked in
 * one pass without keeping the rows, then read again row by row for the load. Any problem refuses the whole file with
 * the row numbers, before anything is written. A byte order mark and Windows line ends are accepted.
 */
final class NeighboursFile {

	static final String EXPORT_ID_PREFIX = "# export_id=";

	/** At most this many problems are named; the count says how many there were. */
	private static final int PROBLEMS_SHOWN = 20;

	private NeighboursFile() {
	}

	/** The file was refused; nothing was put in. */
	static final class RefusedException extends RuntimeException {

		RefusedException(String message) {
			super(message);
		}

	}

	/**
	 * Reads the first row only.
	 * @throws RefusedException when it is not {@code # export_id=<uuid>}
	 */
	static UUID exportId(Path file) {
		try (Stream<String> rows = Files.lines(file, StandardCharsets.UTF_8)) {
			String first = rows.findFirst().map(NeighboursFile::clean).orElse("").replaceFirst("^\\uFEFF", "");
			if (!first.startsWith(EXPORT_ID_PREFIX)) {
				throw new RefusedException("The first row must be " + EXPORT_ID_PREFIX + "<the catalog's export id>");
			}
			try {
				return UUID.fromString(first.substring(EXPORT_ID_PREFIX.length()).strip());
			}
			catch (IllegalArgumentException e) {
				throw new RefusedException("The first row's export id is not a UUID: " + first);
			}
		}
		catch (IOException e) {
			throw unreadable(file, e);
		}
	}

	/**
	 * Checks every row after the first.
	 * @return the number of rows
	 * @throws RefusedException with the first problems and their row numbers
	 */
	static long check(Path file) {
		List<String> problems = new ArrayList<>();
		long problemCount = 0;
		BitSet seen = new BitSet();
		long rowNo = 1;
		try (Stream<String> rows = Files.lines(file, StandardCharsets.UTF_8)) {
			Iterator<String> it = rows.skip(1).iterator();
			while (it.hasNext()) {
				rowNo++;
				String problem;
				try {
					Neighbours row = parse(it.next());
					// Neighbours keeps the book id within int
					problem = seen.get((int) row.bookId()) ? "book " + row.bookId() + " comes twice" : null;
					seen.set((int) row.bookId());
				}
				catch (IllegalArgumentException e) {
					problem = e.getMessage();
				}
				if (problem != null) {
					problemCount++;
					if (problems.size() < PROBLEMS_SHOWN) {
						problems.add("row " + rowNo + ": " + problem);
					}
				}
			}
		}
		catch (IOException e) {
			throw unreadable(file, e);
		}
		catch (UncheckedIOException e) {
			throw new UncheckedIOException("Could not read the neighbours file " + file + " as UTF-8 near row " + rowNo,
					e.getCause());
		}
		if (problemCount > 0) {
			throw new RefusedException("The neighbours file was refused, " + problemCount + " problem(s), nothing put in:\n"
					+ String.join("\n", problems));
		}
		if (rowNo == 1) {
			throw new RefusedException("The neighbours file has no book row, nothing put in");
		}
		return rowNo - 1;
	}

	/**
	 * Hands the rows after the first to {@code load}, read one by one while it runs; call {@link #check} first.
	 * @return what {@code load} returns
	 */
	static long load(Path file, ToLongFunction<Iterator<Neighbours>> load) {
		try (Stream<String> rows = Files.lines(file, StandardCharsets.UTF_8)) {
			return load.applyAsLong(rows.skip(1).map(NeighboursFile::parse).iterator());
		}
		catch (IOException e) {
			throw unreadable(file, e);
		}
	}

	/** @throws IllegalArgumentException with the problem */
	static Neighbours parse(String raw) {
		String row = clean(raw);
		if (row.isBlank()) {
			throw new IllegalArgumentException("a blank row");
		}
		String[] cells = row.split("\t", -1);
		if (cells.length != 2) {
			throw new IllegalArgumentException(cells.length + " columns, not 2 (book_id, neighbour ids)");
		}
		long bookId;
		try {
			bookId = Long.parseLong(cells[0]);
		}
		catch (NumberFormatException e) {
			throw new IllegalArgumentException("book_id is not a number: " + cells[0]);
		}
		String[] ids = cells[1].split(",", -1);
		int[] neighbourIds = new int[ids.length];
		for (int i = 0; i < ids.length; i++) {
			try {
				neighbourIds[i] = Integer.parseInt(ids[i]);
			}
			catch (NumberFormatException e) {
				throw new IllegalArgumentException("book " + bookId + ": a neighbour id is not a number up to "
						+ Integer.MAX_VALUE + ": '" + ids[i] + "'");
			}
		}
		return new Neighbours(bookId, neighbourIds);
	}

	/** {@code Files.lines} splits at {@code \r\n} already; a lone {@code \r} at the end is dropped. */
	private static String clean(String row) {
		return row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
	}

	private static UncheckedIOException unreadable(Path file, IOException e) {
		return new UncheckedIOException("Could not read the neighbours file " + file + " as UTF-8", e);
	}

}
