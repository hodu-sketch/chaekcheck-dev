package com.chaekcheck.collection.internal.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The import's rules files in {@code scripts/library/rules/} (cycle 19, ADR-0114, ADR-0115), read before the
 * collection DB is opened: a missing file or column, or a wrong value, stops the import with the file's name.
 * Format and meaning: {@code scripts/library/rules/README.md}; every rule: docs/maintenance/rules-index.md.
 * <ul>
 * <li>{@code author_publisher_swapped.tsv}: collection libraries whose author and publisher columns were collected
 * the other way round; read swapped. A copy collected on or after {@code swap_before} stops the import (the fixed
 * crawler reads them right; check with {@code scripts/library/check_author_publisher_swap.py} and remove the row,
 * docs/maintenance/3-import.md).</li>
 * <li>{@code supplier_families.tsv}: different names of one supplier, so step ② can join their codes. A supplier not
 * listed is its own family under its {@link #supplierName normalized name}.</li>
 * </ul>
 * @param swapBefore collection id → the first day (Korean time) a copy must not be read swapped any more
 * @param families normalized supplier name → family
 */
record ImportRules(Map<Long, LocalDate> swapBefore, Map<String, String> families) {

	static final String SWAPPED_FILE = "author_publisher_swapped.tsv";

	static final String FAMILIES_FILE = "supplier_families.tsv";

	/** No rules: tests and readers that never group. */
	static final ImportRules NONE = new ImportRules(Map.of(), Map.of());

	/** Company marks taken off supplier names ("(주)교보문고" = "교보문고"), after NFKC and lower case. */
	private static final Pattern COMPANY_MARKS = Pattern.compile("\\(주\\)|주식회사|\\(유\\)|유한회사|\\(사\\)|㈜");

	private static final Pattern SPACES = Pattern.compile("\\s+");

	ImportRules {
		swapBefore = Map.copyOf(swapBefore);
		families = Map.copyOf(families);
	}

	static ImportRules read(Path dir) {
		if (dir == null) {
			throw new IllegalStateException("Set chaekcheck.collection.import.rules-dir (scripts/library/rules)");
		}
		Map<Long, LocalDate> swap = new HashMap<>();
		for (Map<String, String> row : rows(dir, SWAPPED_FILE, "collection_id", "name", "decided_on", "swap_before")) {
			long id;
			try {
				id = Long.parseLong(row.get("collection_id").strip());
			}
			catch (NumberFormatException ex) {
				throw error(SWAPPED_FILE, "not a number in collection_id: " + row.get("collection_id"));
			}
			swap.put(id, date(row.get("swap_before"), SWAPPED_FILE));
		}
		Map<String, String> families = new HashMap<>();
		for (Map<String, String> row : rows(dir, FAMILIES_FILE, "supplier", "family", "decided_on")) {
			String supplier = supplierName(row.get("supplier"));
			String family = row.get("family").strip();
			if (supplier == null || family.isEmpty()) {
				throw error(FAMILIES_FILE, "empty supplier or family: " + row);
			}
			String before = families.put(supplier, family);
			if (before != null && !before.equals(family)) {
				throw error(FAMILIES_FILE, "supplier " + row.get("supplier") + " is in two families: " + before + ", "
						+ family);
			}
		}
		return new ImportRules(swap, families);
	}

	boolean swapped(long collectionId) {
		return swapBefore.containsKey(collectionId);
	}

	/**
	 * The supplier's family for step ②'s code key: the family of {@code supplier_families.tsv}, else the normalized
	 * name; null for an empty supplier (such rows are not joined by code, spec R2-3).
	 */
	String family(String supplier) {
		String name = supplierName(supplier);
		return name == null ? null : families.getOrDefault(name, name);
	}

	/** NFKC, lower case, company marks and spaces removed; null when nothing is left. */
	static String supplierName(String supplier) {
		if (supplier == null) {
			return null;
		}
		String text = Normalizer.normalize(supplier, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
		text = SPACES.matcher(COMPANY_MARKS.matcher(text).replaceAll("")).replaceAll("");
		return text.isEmpty() ? null : text;
	}

	private static LocalDate date(String value, String file) {
		String text = value == null ? "" : value.strip();
		try {
			if (!text.matches("\\d{4}-\\d{2}-\\d{2}")) {
				throw new DateTimeParseException("not YYYY-MM-DD", text, 0);
			}
			return LocalDate.parse(text);
		}
		catch (DateTimeParseException ex) {
			throw error(file, "not a date in swap_before: " + text);
		}
	}

	/** Rows of a tab-separated file with a header (README "꼴"); lines may end in \n or \r\n. */
	private static List<Map<String, String>> rows(Path dir, String name, String... columns) {
		Path file = dir.resolve(name);
		if (!Files.isRegularFile(file)) {
			throw error(name, "rules file missing: " + file.toAbsolutePath());
		}
		String text;
		try {
			text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		if (text.startsWith("﻿")) {
			text = text.substring(1);
		}
		List<String> lines = List.of(text.split("\n", -1)).stream().filter(line -> !line.isEmpty()).toList();
		if (lines.isEmpty()) {
			throw error(name, "empty, a header is needed");
		}
		List<String> header = List.of(lines.getFirst().split("\t", -1));
		for (String column : columns) {
			if (!header.contains(column)) {
				throw error(name, "missing column " + column);
			}
		}
		List<Map<String, String>> rows = new ArrayList<>();
		for (String line : lines.subList(1, lines.size())) {
			String[] cells = line.split("\t", -1);
			Map<String, String> row = new HashMap<>();
			for (int i = 0; i < header.size(); i++) {
				row.put(header.get(i), i < cells.length ? cells[i] : "");
			}
			rows.add(row);
		}
		return rows;
	}

	private static IllegalStateException error(String file, String message) {
		return new IllegalStateException("import rules " + file + ": " + message);
	}

}
