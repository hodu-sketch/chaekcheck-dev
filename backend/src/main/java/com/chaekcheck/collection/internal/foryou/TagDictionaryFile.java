package com.chaekcheck.collection.internal.foryou;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.chaekcheck.picks.BookTagTransfer.DictionaryRow;

/**
 * The {@code tag-dictionary} file (cycle 23 step 3-3, ADR-0149 decision #9, design-delta "넣는 명령" [안]): UTF-8 TSV,
 * header {@code code\taxis\tname\tthema_code}, one tag per row: code of letters, digits and {@code _} (1 to 20, the
 * table's CHECK), axis {@code mood}, {@code subject} or {@code audience}, a name, and a Thema code or nothing. Each code
 * once. The file is the whole dictionary: a code not in it is made inactive, so a file without rows is refused. Any
 * problem refuses the whole file with the row numbers. A byte order mark and Windows line ends are accepted.
 */
final class TagDictionaryFile {

	static final String HEADER = "code\taxis\tname\tthema_code";

	private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_]{1,20}");

	private static final Set<String> AXES = Set.of("mood", "subject", "audience");

	private static final int PROBLEMS_SHOWN = 20;

	private TagDictionaryFile() {
	}

	/** The file was refused; nothing was put in. */
	static final class RefusedException extends RuntimeException {

		RefusedException(String message) {
			super(message);
		}

	}

	static List<DictionaryRow> read(Path file) {
		try {
			return parse(Files.readAllLines(file, StandardCharsets.UTF_8));
		}
		catch (IOException e) {
			throw new UncheckedIOException("Could not read the tag dictionary file " + file + " as UTF-8", e);
		}
	}

	static List<DictionaryRow> parse(List<String> rows) {
		if (rows.isEmpty() || !HEADER.equals(clean(rows.getFirst()).replaceFirst("^\\uFEFF", ""))) {
			throw new RefusedException("The first row must be the header " + HEADER.replace("\t", "\\t"));
		}
		List<String> problems = new ArrayList<>();
		List<DictionaryRow> dictionary = new ArrayList<>();
		Set<String> seen = new HashSet<>();
		for (int i = 1; i < rows.size(); i++) {
			int rowNo = i + 1;
			String row = clean(rows.get(i));
			if (row.isBlank()) {
				problems.add("row " + rowNo + ": a blank row");
				continue;
			}
			String[] cells = row.split("\t", -1);
			if (cells.length != 4) {
				problems.add("row " + rowNo + ": " + cells.length + " columns, not 4");
				continue;
			}
			String code = cells[0];
			if (!CODE.matcher(code).matches()) {
				problems.add("row " + rowNo + ": not a tag code (letters, digits, _; 1 to 20): '" + code + "'");
			}
			else if (!AXES.contains(cells[1])) {
				problems.add("row " + rowNo + ": axis must be one of mood, subject, audience: '" + cells[1] + "'");
			}
			else if (cells[2].isBlank()) {
				problems.add("row " + rowNo + ": code " + code + " has no name");
			}
			else if (!seen.add(code)) {
				problems.add("row " + rowNo + ": code " + code + " comes twice");
			}
			else {
				String thema = cells[3].strip();
				dictionary.add(new DictionaryRow(code, cells[1], cells[2].strip(), thema.isEmpty() ? null : thema));
			}
		}
		if (!problems.isEmpty()) {
			throw new RefusedException("The tag dictionary file was refused, " + problems.size()
					+ " problem(s), nothing put in:\n"
					+ String.join("\n", problems.subList(0, Math.min(PROBLEMS_SHOWN, problems.size()))));
		}
		if (dictionary.isEmpty()) {
			throw new RefusedException("The tag dictionary file has no tag; it would make every code inactive, nothing"
					+ " put in");
		}
		return List.copyOf(dictionary);
	}

	private static String clean(String row) {
		return row.endsWith("\r") ? row.substring(0, row.length() - 1) : row;
	}

}
