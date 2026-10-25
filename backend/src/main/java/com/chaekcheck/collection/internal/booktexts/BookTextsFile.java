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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.AiSearchBookTexts.Line;
import com.chaekcheck.aisearch.AiSearchBookTexts.Source;
import com.chaekcheck.foryou.ForYouTransfer.Blurb;
import com.chaekcheck.foryou.ForYouTransfer.Cover;

/**
 * The {@code book-texts} file (cycle 24, ADR-0151, design-delta "넣는 파일" [안]): UTF-8 TSV, header
 * {@code kind\tbook_id\tsource\tmade_on\ttext}, one text per row. Cycle 24 reads {@code kind=search_line}; since cycle
 * 23 step 3-3 (ADR-0149, design-delta "넣는 명령") also {@code for_you_blurb} (1 to {@value #BLURB_MAX_CHARS}
 * characters), {@code tags} (a comma list of tag codes, empty for none; the book's tags are replaced) and
 * {@code cover_file} (the cover's sha1, 40 lower-case hex digits). Those three do not read {@code source}, and
 * {@code cover_file} does not read {@code made_on}. Other kinds are skipped and counted. A row with a tab or a line
 * break in its text has the wrong number of columns, and a text over {@value AiSearchBookTexts#LINE_MAX_CHARS}
 * characters is refused: then the whole file is refused, with every problem's row number, and nothing is put in. One
 * row per book and kind. A byte order mark and Windows line ends are accepted.
 */
public final class BookTextsFile {

	static final String HEADER = "kind\tbook_id\tsource\tmade_on\ttext";

	static final String SEARCH_LINE = "search_line";

	static final String FOR_YOU_BLURB = "for_you_blurb";

	static final String TAGS = "tags";

	static final String COVER_FILE = "cover_file";

	private static final List<String> KINDS = List.of(SEARCH_LINE, FOR_YOU_BLURB, TAGS, COVER_FILE);

	/** {@code foryou.book_blurb}'s CHECK (cycle 23; the PC keeps 80-150, R8-4). */
	static final int BLURB_MAX_CHARS = 300;

	/** {@code picks.tag_dictionary}'s code CHECK. */
	static final Pattern TAG_CODE = Pattern.compile("[A-Za-z0-9_]{1,20}");

	/** {@code foryou.book_cover}'s file_key CHECK. */
	static final Pattern COVER_KEY = Pattern.compile("[0-9a-f]{40}");

	private static final int COLUMNS = 5;

	/** At most this many problems are named; the count says how many there were. */
	private static final int PROBLEMS_SHOWN = 20;

	private BookTextsFile() {
	}

	/**
	 * @param lines the search lines, in file order
	 * @param blurbs the {@code for_you_blurb} rows (cycle 23)
	 * @param tags the {@code tags} rows (cycle 23)
	 * @param covers the {@code cover_file} rows (cycle 23)
	 * @param skippedKinds the rows of other kinds, by kind
	 * @param rows the data rows read
	 */
	public record Contents(List<Line> lines, List<Blurb> blurbs, List<BookTags> tags, List<Cover> covers,
			Map<String, Integer> skippedKinds, int rows) {

		public Contents {
			lines = List.copyOf(lines);
			blurbs = List.copyOf(blurbs);
			tags = List.copyOf(tags);
			covers = List.copyOf(covers);
			skippedKinds = Map.copyOf(skippedKinds);
		}

		/** Whether the file has a row of cycle 23's kinds. */
		public boolean hasForYouKinds() {
			return !blurbs.isEmpty() || !tags.isEmpty() || !covers.isEmpty();
		}

	}

	/** @param codes the book's tag codes, in file order, each once; empty takes every tag off */
	public record BookTags(long bookId, List<String> codes, LocalDate madeOn) {

		public BookTags {
			codes = List.copyOf(codes);
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
		List<Blurb> blurbs = new ArrayList<>();
		List<BookTags> tags = new ArrayList<>();
		List<Cover> covers = new ArrayList<>();
		Map<String, Integer> skipped = new TreeMap<>();
		Map<String, Set<Long>> seen = new TreeMap<>();
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
			String kind = cells[0];
			if (!KINDS.contains(kind)) {
				skipped.merge(kind, 1, Integer::sum);
				continue;
			}
			try {
				long bookId = Long.parseLong(cells[1]);
				String text = cells[4];
				switch (kind) {
					case SEARCH_LINE -> {
						Line line = new Line(bookId, Source.of(cells[2]), LocalDate.parse(cells[3]), text);
						if (once(seen, kind, bookId, rowNo, problems)) {
							lines.add(line);
						}
					}
					case FOR_YOU_BLURB -> {
						checkBookId(bookId);
						LocalDate madeOn = LocalDate.parse(cells[3]);
						int length = text.codePointCount(0, text.length());
						if (text.isBlank() || length > BLURB_MAX_CHARS) {
							problems.add("row " + rowNo + ": a blurb must have 1 to " + BLURB_MAX_CHARS
									+ " characters, book " + bookId + " has " + length);
						}
						else if (once(seen, kind, bookId, rowNo, problems)) {
							blurbs.add(new Blurb(bookId, text, madeOn));
						}
					}
					case TAGS -> {
						checkBookId(bookId);
						LocalDate madeOn = LocalDate.parse(cells[3]);
						Set<String> codes = new LinkedHashSet<>();
						List<String> bad = new ArrayList<>();
						if (!text.isEmpty()) {
							for (String code : text.split(",", -1)) {
								String trimmed = code.strip();
								if (TAG_CODE.matcher(trimmed).matches()) {
									codes.add(trimmed);
								}
								else {
									bad.add("'" + code + "'");
								}
							}
						}
						if (!bad.isEmpty()) {
							problems.add("row " + rowNo + ": not a tag code (letters, digits, _; 1 to 20): "
									+ String.join(", ", bad));
						}
						else if (once(seen, kind, bookId, rowNo, problems)) {
							tags.add(new BookTags(bookId, List.copyOf(codes), madeOn));
						}
					}
					default -> { // COVER_FILE
						checkBookId(bookId);
						if (!COVER_KEY.matcher(text).matches()) {
							problems.add("row " + rowNo + ": a cover file key is 40 lower-case hex digits (sha1): " + text);
						}
						else if (once(seen, kind, bookId, rowNo, problems)) {
							covers.add(new Cover(bookId, text));
						}
					}
				}
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
		return new Contents(lines, blurbs, tags, covers, skipped, rows.size() - 1);
	}

	/** Whether this is the book's first row of the kind; a second one is a problem. */
	private static boolean once(Map<String, Set<Long>> seen, String kind, long bookId, int rowNo, List<String> problems) {
		if (seen.computeIfAbsent(kind, k -> new HashSet<>()).add(bookId)) {
			return true;
		}
		problems.add("row " + rowNo + ": a second " + (kind.equals(SEARCH_LINE) ? "line" : kind + " row") + " for book "
				+ bookId);
		return false;
	}

	/** The search line's rule for the other kinds: 1 or more. */
	private static void checkBookId(long bookId) {
		if (bookId < 1) {
			throw new IllegalArgumentException("a row needs a book id of 1 or more: " + bookId);
		}
	}

	/** Windows line ends: {@code readAllLines} splits at {@code \r\n} already; a lone {@code \r} at the end is dropped. */
	private static String clean(String row) {
		return row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
	}

}
