package com.chaekcheck.collection.internal.libraryinsert;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The rules folder {@code scripts/library/rules/} (plan ADR-0085), read as plain values. Files, columns and sources:
 * {@code scripts/library/rules/README.md}. Every file is tab separated with a header and no quoting. A missing file
 * or column, or a value outside the known sets, is a rules error (exit 1, no report), as in the old seed script
 * ({@code build_library_seed.py load_rules}).
 * @param goneKnown linked libraries a person checked as gone and left in the service ({@code gone_known.tsv}, user
 * choice 2026-10-12): counted in the report, not listed as gone
 * @param fileHashes SHA-256 of every file read, by file name: they go into the report, so a changed rules file
 * changes the report id even when it changes no library (AC-5)
 */
record RuleSet(Map<Long, String> demoOrClosed, Map<Long, Long> sameSite, Set<Long> sameHostKept, Set<Long> goneKnown,
		Map<Long, Integer> orderNoAllowed, Map<Long, String> kinds, Map<String, String> kyoboProbe,
		Map<String, String> finalOrigins, Map<Long, NameOverride> nameOverrides, Map<Long, Volume> bookcubeVolumes,
		Map<Long, PublicJoin> publicJoins, LocalDate joinSurveyedOn, Map<String, String> fileHashes) {

	static final Set<String> MEMBERS_ONLY_KINDS = Set.of("SCHOOL", "UNIV", "COMPANY", "GOV", "ORG");

	static final Set<String> KINDS = Set.of("SCHOOL", "UNIV", "COMPANY", "GOV", "ORG", "PUBLIC", "UNKNOWN", "TEST");

	static final Set<String> PROBE_MODELS = Set.of("OWNED", "MIXED", "RENTAL");

	static final Set<String> ELIGIBILITIES = Set.of("REGION", "OPEN", "OPEN_CAUTION", "UNVERIFIED", "AFFILIATION");

	/** A service library's name change (cycle 08, 09): applied only where the name is still {@code oldName}. */
	record NameOverride(String oldName, String newName) {
	}

	/** A BOOKCUBE site's title and volume counts; null counts when the check failed. */
	record Volume(Integer totalItem, Integer totalVolume) {
	}

	/** One eligibility rule: a region id, or null for NATIONWIDE. */
	record Rule(String basis, Long regionId) {
	}

	/** One row of the public libraries' join survey (120 rows of 2026-10-05), regions already resolved. */
	record PublicJoin(String eligibility, boolean visitRequired, String joinMethod, String extraCondition,
			String joinCaution, String sourceUrl, List<Rule> rules, String eligibilitySummary) {
	}

	static RuleSet read(Path dir) {
		Map<String, String> hashes = new TreeMap<>();
		Reader reader = new Reader(dir, hashes);

		Map<Long, String> demo = new HashMap<>();
		for (Map<String, String> row : reader.rows("demo_or_closed.tsv", "collection_id", "name")) {
			demo.put(id(row, "collection_id"), row.get("name"));
		}
		Map<Long, Long> sameSite = new HashMap<>();
		for (Map<String, String> row : reader.rows("same_site.tsv", "collection_id", "library_id")) {
			sameSite.put(id(row, "collection_id"), id(row, "library_id"));
		}
		Set<Long> kept = new java.util.HashSet<>();
		for (Map<String, String> row : reader.rows("same_host_kept.tsv", "collection_id")) {
			kept.add(id(row, "collection_id"));
		}
		Set<Long> goneKnown = new java.util.HashSet<>();
		for (Map<String, String> row : reader.rows("gone_known.tsv", "collection_id")) {
			goneKnown.add(id(row, "collection_id"));
		}
		Map<Long, Integer> allowed = new HashMap<>();
		for (Map<String, String> row : reader.rows("order_no_allowed.tsv", "collection_id", "order_no")) {
			allowed.put(id(row, "collection_id"), Math.toIntExact(id(row, "order_no")));
		}
		Map<Long, String> kinds = new HashMap<>();
		for (Map<String, String> row : reader.rows("library_kinds.tsv", "collection_id", "kind")) {
			String kind = row.get("kind");
			if (!KINDS.contains(kind)) {
				throw LibraryInsertException.rules("library_kinds.tsv: unknown kind " + kind + " ("
						+ row.get("collection_id") + ")");
			}
			kinds.put(id(row, "collection_id"), kind);
		}
		Map<String, String> probe = new HashMap<>();
		for (Map<String, String> row : reader.rows("kyobo_probe.tsv", "site_url", "lending_model")) {
			String model = row.get("lending_model");
			if (!PROBE_MODELS.contains(model)) {
				throw LibraryInsertException.rules("kyobo_probe.tsv: unknown lending_model " + model);
			}
			probe.put(stripSlash(row.get("site_url")), model);
		}
		Map<String, String> finals = new HashMap<>();
		for (Map<String, String> row : reader.rows("final_origins.tsv", "listed_base_url", "base_url")) {
			finals.put(row.get("listed_base_url"), row.get("base_url"));
		}
		Map<Long, NameOverride> names = new HashMap<>();
		for (Map<String, String> row : reader.rows("name_overrides.tsv", "library_id", "old_name", "new_name")) {
			names.put(id(row, "library_id"), new NameOverride(row.get("old_name"), row.get("new_name")));
		}
		Map<Long, Volume> volumes = new HashMap<>();
		for (Map<String, String> row : reader.rows("bookcube_volume.tsv", "collection_id", "total_item",
				"total_volume")) {
			String items = row.get("total_item").strip(), volume = row.get("total_volume").strip();
			volumes.put(id(row, "collection_id"), items.isEmpty() || volume.isEmpty() ? new Volume(null, null)
					: new Volume(Integer.valueOf(items), Integer.valueOf(volume)));
		}
		Map<Long, PublicJoin> joins = new HashMap<>();
		for (Map<String, String> row : reader.rows("public_join.tsv", "collection_id", "eligibility",
				"visit_required", "join_method", "extra_condition", "join_caution", "source_url", "rules",
				"eligibility_summary")) {
			long collectionId = id(row, "collection_id");
			String eligibility = row.get("eligibility");
			if (!ELIGIBILITIES.contains(eligibility)) {
				throw LibraryInsertException.rules("public_join.tsv " + collectionId + ": unknown eligibility "
						+ eligibility);
			}
			List<Rule> rules = rules(row.get("rules"), collectionId);
			if (Set.of("REGION", "OPEN", "OPEN_CAUTION").contains(eligibility) && rules.isEmpty()) {
				throw LibraryInsertException.rules("public_join.tsv " + collectionId
						+ ": no rules; run scripts/library/resolve_regions.py");
			}
			joins.put(collectionId, new PublicJoin(eligibility, "true".equals(row.get("visit_required")),
					text(row.get("join_method")), text(row.get("extra_condition")), text(row.get("join_caution")),
					text(row.get("source_url")), rules, text(row.get("eligibility_summary"))));
		}
		LocalDate surveyedOn = reader.date("join_surveyed_on.txt");
		return new RuleSet(Map.copyOf(demo), Map.copyOf(sameSite), Set.copyOf(kept), Set.copyOf(goneKnown),
				Map.copyOf(allowed),
				Map.copyOf(kinds), Map.copyOf(probe), Map.copyOf(finals), Map.copyOf(names), Map.copyOf(volumes),
				Map.copyOf(joins), surveyedOn, java.util.Collections.unmodifiableMap(hashes));
	}

	/** {@code RESIDENCE:41;WORK:NATIONWIDE} as rules. */
	static List<Rule> rules(String text, long collectionId) {
		List<Rule> rules = new ArrayList<>();
		for (String part : text.split(";")) {
			if (part.isBlank()) {
				continue;
			}
			String[] pair = part.split(":");
			if (pair.length != 2 || !Set.of("RESIDENCE", "WORK", "SCHOOL", "AFFILIATION").contains(pair[0])) {
				throw LibraryInsertException.rules("public_join.tsv " + collectionId + ": bad rule " + part);
			}
			try {
				rules.add(new Rule(pair[0], "NATIONWIDE".equals(pair[1]) ? null : Long.valueOf(pair[1])));
			}
			catch (NumberFormatException ex) {
				throw LibraryInsertException.rules("public_join.tsv " + collectionId + ": bad region " + part);
			}
		}
		return List.copyOf(rules);
	}

	/** The probe's way of matching addresses: without the trailing {@code /} (plan research 1-4). */
	static String stripSlash(String url) {
		String out = url;
		while (out.endsWith("/")) {
			out = out.substring(0, out.length() - 1);
		}
		return out;
	}

	private static String text(String value) {
		return value == null || value.isBlank() ? null : value.strip();
	}

	private static long id(Map<String, String> row, String column) {
		try {
			return Long.parseLong(row.get(column).strip());
		}
		catch (NumberFormatException ex) {
			throw LibraryInsertException.rules("not a number in " + column + ": " + row.get(column));
		}
	}

	/** Reads files of the folder and keeps their hashes. */
	private record Reader(Path dir, Map<String, String> hashes) {

		List<Map<String, String>> rows(String name, String... columns) {
			List<String> lines = lines(name);
			if (lines.isEmpty()) {
				throw LibraryInsertException.rules(name + ": empty, a header is needed");
			}
			List<String> header = List.of(lines.getFirst().split("\t", -1));
			for (String column : columns) {
				if (!header.contains(column)) {
					throw LibraryInsertException.rules(name + ": missing column " + column);
				}
			}
			List<Map<String, String>> rows = new ArrayList<>();
			for (String line : lines.subList(1, lines.size())) {
				if (line.isEmpty()) {
					continue;
				}
				String[] cells = line.split("\t", -1);
				Map<String, String> row = new HashMap<>();
				for (int i = 0; i < header.size(); i++) {
					row.put(header.get(i), i < cells.length ? cells[i] : "");
				}
				rows.add(row);
			}
			return rows;
		}

		LocalDate date(String name) {
			List<String> lines = lines(name);
			String value = lines.isEmpty() ? "" : lines.getFirst().strip();
			try {
				if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
					throw new DateTimeParseException("not YYYY-MM-DD", value, 0);
				}
				return LocalDate.parse(value);
			}
			catch (DateTimeParseException ex) {
				throw LibraryInsertException.rules(name + ": not a date: " + value);
			}
		}

		/** Lines without line ends ({@code \n} or {@code \r\n}: git may check the files out either way). */
		private List<String> lines(String name) {
			Path file = dir.resolve(name);
			if (!Files.isRegularFile(file)) {
				throw LibraryInsertException.rules("rules file missing: " + file);
			}
			try {
				byte[] bytes = Files.readAllBytes(file);
				String text = new String(bytes, StandardCharsets.UTF_8).replace("\r\n", "\n");
				if (text.startsWith("﻿")) {
					text = text.substring(1);
				}
				// the hash is of the text with \n line ends, so a checkout with \r\n gives the same report
				hashes.put(name, sha256(text));
				return List.of(text.split("\n", -1)).stream().filter(line -> !line.isEmpty()).toList();
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

	}

	static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

}
