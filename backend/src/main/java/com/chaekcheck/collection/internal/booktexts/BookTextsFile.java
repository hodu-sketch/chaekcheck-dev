package com.chaekcheck.collection.internal.booktexts;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;

/**
 * The {@code book-texts} file (cycle 24, ADR-0151, design-delta "넣는 파일" [안]): UTF-8 TSV, header
 * {@code kind\tbook_id\tsource\tmade_on\ttext}, one text per row. This cycle reads {@code kind=search_line}; other kinds
 * (cycle 23's texts later) are skipped and counted. A row with a tab or a line break in its text has the wrong number
 * of columns, and a text over {@value AiSearchBookTexts#LINE_MAX_CHARS} characters is refused: then the whole file is
 * refused, with every problem's row number, and nothing is put in. A byte order mark and Windows line ends are
 * accepted.
 */
public final class BookTextsFile {

	static final String HEADER = "kind\tbook_id\tsource\tmade_on\ttext";

	static final String SEARCH_LINE = "search_line";

	private static final int COLUMNS = 5;

	/** At most this many problems are named; the count says how many there were. */
	private static final int PROBLEMS_SHOWN = 20;

	private BookTextsFile() {
	}

	/**
	 * @param lines the search lines, in file order
	 * @param skippedKinds the rows of other kinds, by kind
	 * @param rows the data rows read
	 */
	public record Contents(List<Line> lines, Map<String, Integer> skippedKinds, int rows) {

		public Contents {
			lines = List.copyOf(lines);
			skippedKinds = Map.copyOf(skippedKinds);
		}

	}

	/** The file was refused; nothing was put in. */
	public static final class RefusedException extends RuntimeException {

		RefusedException(String message) {
			super(message);
		}

	}

	/** Reads and checks the whole file before anything is written. */
	public static Contents read(Path file) {
		List<String> rows;
		try {
			rows = Files.readAllLines(file, StandardCharsets.UTF_8);
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read the book-texts file " + file + " as UTF-8", e);
		}
		return parse(rows);
	}

	static Contents parse(List<String> rows) {
		if (rows.isEmpty() || !HEADER.equals(clean(rows.getFirst()).replaceFirst("^\\uFEFF", ""))) {
			throw new RefusedException("The first row must be the header " + HEADER.replace("\t", "\\t"));
		}
		List<String> problems = new ArrayList<>();
		List<Line> lines = new ArrayList<>();
		Map<String, Integer> skipped = new TreeMap<>();
		Set<Long> seen = new HashSet<>();
		for (int i = 1; i < rows.size(); i++) {
			int rowNo = i + 1;
			String row = clean(rows.get(i));
			if (row.isBlank()) {
				problems.add("row " + rowNo + ": a blank row (a line break inside a text, or an empty row at the end?)");
				continue;
			}
			String[] cells = row.split("\t", -1);
			if (cells.length != COLUMNS) {
				problems.add("row " + rowNo + ": " + cells.length + " columns, not " + COLUMNS
						+ " (a tab or a line break inside the text?)");
				continue;
			}
			if (!SEARCH_LINE.equals(cells[0])) {
				skipped.merge(cells[0], 1, Integer::sum);
				continue;
			}
			try {
				long bookId = Long.parseLong(cells[1]);
				Line line = new Line(bookId, Source.of(cells[2]), LocalDate.parse(cells[3]), cells[4]);
				if (!seen.add(bookId)) {
					problems.add("row " + rowNo + ": a second line for book " + bookId);
					continue;
				}
				lines.add(line);
			}
			catch (NumberFormatException e) {
				problems.add("row " + rowNo + ": book_id is not a number: " + cells[1]);
			}
			catch (DateTimeParseException e) {
				problems.add("row " + rowNo + ": made_on is not a date (YYYY-MM-DD): " + cells[3]);
			}
			catch (IllegalArgumentException e) {
				problems.add("row " + rowNo + ": " + e.getMessage());
			}
		}
		if (!problems.isEmpty()) {
			throw new RefusedException("The book-texts file was refused, " + problems.size() + " problem(s), nothing put in:\n"
					+ String.join("\n", problems.subList(0, Math.min(PROBLEMS_SHOWN, problems.size()))));
		}
		return new Contents(lines, skipped, rows.size() - 1);
	}

	/** Windows line ends: {@code readAllLines} splits at {@code \r\n} already; a lone {@code \r} at the end is dropped. */
	private static String clean(String row) {
		return row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
	}

}
