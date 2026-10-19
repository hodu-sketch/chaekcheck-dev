package com.chaekcheck.picks.internal.lists;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.chaekcheck.picks.CuratedListTransfer;
import com.chaekcheck.picks.CuratedListTransfer.ListFileException;

/**
 * Reads the list files (design-delta "목록 파일", ADR-0128): UTF-8, tab-separated, a header line naming the columns
 * (in any order; columns the reader does not know are left alone), an empty cell for no value. Every value is checked
 * the way the {@code picks} tables check it, so a file that reads here also stores: a wrong value stops with the file,
 * the line and the column.
 */
final class ListFiles {

	static final String LEEDONGJIN = "leedongjin";

	static final String SNU100 = "snu100";

	/** KAIST library's 사서추천도서 2026·2025·2024 (user 2026-10-19, 2026-10-20; 스페셜도서 before). */
	static final String KAIST = "kaist";

	/** The lists {@code fields.tsv} may name. */
	static final Set<String> LISTS = Set.of(LEEDONGJIN, SNU100, KAIST);

	private static final Pattern ISBN13 = Pattern.compile("97[89][0-9]{10}");

	private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private static final Set<String> PINNED_BY = Set.of("collector", "manual");

	/** One field of a list, as {@code fields.tsv} has it. */
	record Field(String list, String code, String name, int position) {
	}

	/**
	 * One fixed edition (ADR-0124): what the collector chose or a person wrote.
	 * @param by {@code collector}, {@code manual} or null
	 */
	record Pinned(String title, String author, String publisher, String isbn13, String by) {

		boolean isEmpty() {
			return isbn13 == null && title == null;
		}

	}

	/**
	 * One book row of {@code leedongjin.tsv}, {@code snu100.tsv} or {@code kaist.tsv}.
	 * @param position Lee Dong-jin: order inside the month; SNU: the table-of-contents number; KAIST: order inside the
	 * field
	 * @param month Lee Dong-jin only
	 * @param tocNo SNU only
	 * @param coverUrl the address the collector took the cover from, https; its file is
	 * {@code covers/<sha1(coverUrl)>.jpg}
	 * @param checkedOn when the collector last saw the video public (Lee Dong-jin), or null
	 */
	record Row(String list, int line, String field, int position, String title, String author, String publisher,
			String isbn13, YearMonth month, Integer yearBook, String videoId, Integer tocNo, String coverUrl,
			Pinned pinned, LocalDate checkedOn) {
	}

	record Lists(List<Field> fields, List<Row> rows) {
	}

	private ListFiles() {
	}

	static Lists read(Path dir) {
		if (!Files.isDirectory(dir)) {
			throw new ListFileException(dir.toString(), 0, "the lists folder does not exist");
		}
		for (String name : CuratedListTransfer.LIST_FILES) {
			if (!Files.isRegularFile(dir.resolve(name))) {
				throw new ListFileException(name, 0, "missing in " + dir);
			}
		}
		List<Field> fields = fields(dir.resolve("fields.tsv"));
		Set<String> codes = new HashSet<>();
		fields.forEach(field -> codes.add(field.list() + "/" + field.code()));
		List<Row> rows = new ArrayList<>(leedongjin(dir.resolve("leedongjin.tsv"), codes));
		rows.addAll(snu100(dir.resolve("snu100.tsv"), codes));
		rows.addAll(kaist(dir.resolve("kaist.tsv"), codes));
		return new Lists(List.copyOf(fields), List.copyOf(rows));
	}

	private static List<Field> fields(Path file) {
		List<Field> fields = new ArrayList<>();
		Set<String> keys = new HashSet<>();
		Set<String> places = new HashSet<>();
		for (Line line : lines(file, "list", "code", "name", "position")) {
			String list = line.required("list");
			if (!LISTS.contains(list)) {
				throw line.wrong("list", "is not leedongjin, snu100 or kaist");
			}
			String code = line.required("code");
			if (code.length() > 20) {
				throw line.wrong("code", "is longer than 20 characters");
			}
			int position = line.number("position", 1, Short.MAX_VALUE);
			if (!keys.add(list + "/" + code)) {
				throw line.wrong("code", "is there twice for " + list);
			}
			if (!places.add(list + "/" + position)) {
				throw line.wrong("position", "is there twice for " + list);
			}
			fields.add(new Field(list, code, line.required("name"), position));
		}
		return fields;
	}

	private static List<Row> leedongjin(Path file, Set<String> codes) {
		List<Row> rows = new ArrayList<>();
		Set<String> places = new HashSet<>();
		for (Line line : lines(file, "month", "position", "year_book", "video_id", "field", "title", "author",
				"publisher", "isbn13", "cover_url", "pinned_title", "pinned_author", "pinned_publisher",
				"pinned_isbn13", "pinned_by", "checked_on")) {
			YearMonth month = line.month("month");
			int position = line.number("position", 1, Integer.MAX_VALUE);
			if (!places.add(month + "/" + position)) {
				throw line.wrong("position", "is there twice for " + month);
			}
			Integer yearBook = line.optionalNumber("year_book", 2000, 2100);
			String videoId = line.required("video_id");
			if (!VIDEO_ID.matcher(videoId).matches()) {
				throw line.wrong("video_id", "is not a video id");
			}
			rows.add(new Row(LEEDONGJIN, line.number, field(line, LEEDONGJIN, codes), position, line.required("title"),
					line.value("author"), line.value("publisher"), line.isbn("isbn13"), month, yearBook, videoId, null,
					line.https("cover_url"), pinned(line), line.date("checked_on")));
		}
		return rows;
	}

	private static List<Row> snu100(Path file, Set<String> codes) {
		List<Row> rows = new ArrayList<>();
		Set<Integer> numbers = new HashSet<>();
		for (Line line : lines(file, "toc_no", "field", "title", "author", "cover_url", "pinned_title",
				"pinned_author", "pinned_publisher", "pinned_isbn13", "pinned_by")) {
			int tocNo = line.number("toc_no", 1, 100);
			if (!numbers.add(tocNo)) {
				throw line.wrong("toc_no", "is there twice");
			}
			rows.add(new Row(SNU100, line.number, field(line, SNU100, codes), tocNo, line.required("title"),
					line.value("author"), null, null, null, null, null, tocNo, line.https("cover_url"), pinned(line),
					null));
		}
		return rows;
	}

	/**
	 * KAIST's 사서추천도서 (user 2026-10-20: "사서추천도서로 바꿔줘", "2026, 2025, 2024 다 해줘"): the recommendation
	 * {@code year} (kept as {@code year_book}), position 1..n once per year (KAIST's own order), a field chosen by the
	 * book's subject; the row's own ISBN, publisher and cover as given.
	 */
	private static List<Row> kaist(Path file, Set<String> codes) {
		List<Row> rows = new ArrayList<>();
		Set<String> places = new HashSet<>();
		for (Line line : lines(file, "year", "field", "position", "title", "author", "publisher", "isbn13",
				"cover_url", "pinned_title", "pinned_author", "pinned_publisher", "pinned_isbn13", "pinned_by")) {
			int year = line.number("year", 2000, 2100);
			String field = field(line, KAIST, codes);
			int position = line.number("position", 1, Short.MAX_VALUE);
			if (!places.add(year + "/" + position)) {
				throw line.wrong("position", "is there twice for " + year);
			}
			rows.add(new Row(KAIST, line.number, field, position, line.required("title"), line.value("author"),
					line.value("publisher"), line.isbn("isbn13"), null, year, null, null, line.https("cover_url"),
					pinned(line), null));
		}
		return rows;
	}

	private static String field(Line line, String list, Set<String> codes) {
		String field = line.required("field");
		if (!codes.contains(list + "/" + field)) {
			throw line.wrong("field", "is not a " + list + " field of fields.tsv");
		}
		return field;
	}

	private static Pinned pinned(Line line) {
		String by = line.value("pinned_by");
		if (by != null && !PINNED_BY.contains(by)) {
			throw line.wrong("pinned_by", "is not collector or manual");
		}
		return new Pinned(line.value("pinned_title"), line.value("pinned_author"), line.value("pinned_publisher"),
				line.isbn("pinned_isbn13"), by);
	}

	private static List<Line> lines(Path file, String... required) {
		String name = file.getFileName().toString();
		List<String> text;
		try {
			text = Files.readAllLines(file, StandardCharsets.UTF_8);
		}
		catch (CharacterCodingException ex) {
			throw new ListFileException(name, 0, "is not UTF-8");
		}
		catch (IOException ex) {
			throw new UncheckedIOException("Cannot read " + file, ex);
		}
		if (text.isEmpty()) {
			throw new ListFileException(name, 1, "has no header line");
		}
		String[] header = cells(text.getFirst());
		Map<String, Integer> columns = new HashMap<>();
		for (int i = 0; i < header.length; i++) {
			columns.put(header[i].strip(), i);
		}
		for (String column : required) {
			if (!columns.containsKey(column)) {
				throw new ListFileException(name, 1, "has no column " + column);
			}
		}
		List<Line> lines = new ArrayList<>();
		for (int i = 1; i < text.size(); i++) {
			if (!text.get(i).isBlank()) {
				lines.add(new Line(name, i + 1, columns, cells(text.get(i))));
			}
		}
		return lines;
	}

	private static String[] cells(String line) {
		String text = line.startsWith("﻿") ? line.substring(1) : line;
		return text.split("\t", -1);
	}

	/** One line of a file, read by column name. */
	private record Line(String file, int number, Map<String, Integer> columns, String[] cells) {

		/** The cell stripped, or null when empty or missing. */
		String value(String column) {
			Integer at = columns.get(column);
			if (at == null || at >= cells.length) {
				return null;
			}
			String value = cells[at].strip();
			return value.isEmpty() ? null : value;
		}

		String required(String column) {
			String value = value(column);
			if (value == null) {
				throw wrong(column, "is empty");
			}
			return value;
		}

		int number(String column, int min, int max) {
			Integer value = optionalNumber(column, min, max);
			if (value == null) {
				throw wrong(column, "is empty");
			}
			return value;
		}

		Integer optionalNumber(String column, int min, int max) {
			String value = value(column);
			if (value == null) {
				return null;
			}
			if (!value.matches("[0-9]{1,9}")) {
				throw wrong(column, "is not a number");
			}
			int number = Integer.parseInt(value);
			if (number < min || number > max) {
				throw wrong(column, "is not between " + min + " and " + max);
			}
			return number;
		}

		String isbn(String column) {
			String value = value(column);
			if (value != null && !ISBN13.matcher(value).matches()) {
				throw wrong(column, "is not an ISBN-13");
			}
			return value;
		}

		String https(String column) {
			String value = value(column);
			if (value != null && !value.startsWith("https://")) {
				throw wrong(column, "does not start with https://");
			}
			return value;
		}

		YearMonth month(String column) {
			String value = required(column);
			try {
				if (value.matches("[0-9]{4}-[0-9]{2}")) {
					return YearMonth.parse(value);
				}
			}
			catch (DateTimeParseException ex) {
				// below
			}
			throw wrong(column, "is not YYYY-MM");
		}

		LocalDate date(String column) {
			String value = value(column);
			if (value == null) {
				return null;
			}
			try {
				return LocalDate.parse(value);
			}
			catch (DateTimeParseException ex) {
				throw wrong(column, "is not YYYY-MM-DD");
			}
		}

		ListFileException wrong(String column, String what) {
			return new ListFileException(file, number, column + " " + what);
		}

	}

}
