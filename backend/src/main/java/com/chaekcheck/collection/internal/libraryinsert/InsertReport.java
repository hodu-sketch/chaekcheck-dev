package com.chaekcheck.collection.internal.libraryinsert;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The preview report of the library-insert command (plan cycle 13 R2-2, R2-4): normalized JSON of the plan, the
 * copy's file name, the SHA-256 of every rules file and the join survey date. The report id is the first 12 hex
 * digits of the SHA-256 of that JSON; the file {@code library-insert-<id>.json} adds the id and the time it was made,
 * which the id leaves out. An insert recomputes the report and goes on only when it equals the file (R2-2 ②).
 */
final class InsertReport {

	/** Sorted keys, so the same content always gives the same text and id. */
	static final JsonMapper JSON = JsonMapper.builder()
		.enable(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY)
		.enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
		.build();

	private static final String ID = "reportId", CREATED = "createdAt";

	private final ObjectNode content;

	private final String id;

	private InsertReport(ObjectNode content) {
		this.content = content;
		this.id = RuleSet.sha256(JSON.writeValueAsString(content)).substring(0, 12);
	}

	static InsertReport of(InsertPlan plan, RuleSet rules, String snapshot) {
		Map<String, Object> content = new LinkedHashMap<>();
		content.put("snapshot", snapshot);
		content.put("rulesFiles", rules.fileHashes());
		content.put("joinSurveyedOn", rules.joinSurveyedOn().toString());
		content.put("firstId", plan.firstId());
		content.put("newLibraries", plan.newLibraries());
		content.put("changed", plan.changed());
		content.put("gone", plan.gone());
		content.put("goneKnown", plan.goneKnown());
		content.put("suspects", plan.suspects());
		content.put("leftOut", plan.leftOut());
		// through text, so a computed report and a read one are the same kind of tree
		return new InsertReport((ObjectNode) JSON.readTree(JSON.writeValueAsString(content)));
	}

	String id() {
		return id;
	}

	static Path file(Path dir, String id) {
		return dir.resolve("library-insert-" + id + ".json");
	}

	Path write(Path dir, Instant createdAt) {
		ObjectNode out = content.deepCopy();
		out.put(ID, id);
		out.put(CREATED, createdAt.toString());
		Path file = file(dir, id);
		try {
			Files.createDirectories(dir);
			Files.writeString(file, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(out),
					StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		return file;
	}

	/**
	 * Checks this (recomputed) report against the file of {@code id}.
	 * @throws LibraryInsertException exit 4 when the file is missing or another id, listing the parts that differ
	 */
	void requireSameAs(Path dir, String reportId) {
		Path file = file(dir, reportId);
		if (reportId == null || reportId.isBlank() || !Files.isRegularFile(file)) {
			throw new LibraryInsertException(LibraryInsertException.REPORT,
					"no report " + file + "; run the preview first and give its id");
		}
		ObjectNode stored;
		try {
			stored = (ObjectNode) JSON.readTree(Files.readString(file, StandardCharsets.UTF_8));
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
		stored.remove(ID);
		stored.remove(CREATED);
		if (reportId.equals(id) && stored.equals(content)) {
			return;
		}
		throw new LibraryInsertException(LibraryInsertException.REPORT, "the report " + reportId
				+ " differs from a new computation (" + id + "): " + String.join("; ", differences(stored, content)));
	}

	/** Top-level parts that differ; inside {@code rulesFiles} the file names, inside lists the count. */
	static List<String> differences(ObjectNode stored, ObjectNode now) {
		List<String> out = new ArrayList<>();
		for (Iterator<String> names = union(stored, now); names.hasNext();) {
			String name = names.next();
			JsonNode a = stored.get(name), b = now.get(name);
			if (a != null && a.equals(b)) {
				continue;
			}
			if ("rulesFiles".equals(name) && a != null && b != null) {
				List<String> files = new ArrayList<>();
				for (Iterator<String> fileNames = union((ObjectNode) a, (ObjectNode) b); fileNames.hasNext();) {
					String fileName = fileNames.next();
					if (!String.valueOf(a.get(fileName)).equals(String.valueOf(b.get(fileName)))) {
						files.add(fileName);
					}
				}
				out.add("rules files " + files);
			}
			else if (a != null && b != null && a.isArray() && b.isArray()) {
				out.add(name + " (" + a.size() + " in the report, " + b.size() + " now)");
			}
			else {
				out.add(name + " (" + a + " in the report, " + b + " now)");
			}
		}
		return out;
	}

	private static Iterator<String> union(ObjectNode a, ObjectNode b) {
		java.util.TreeSet<String> names = new java.util.TreeSet<>(a.propertyNames());
		names.addAll(b.propertyNames());
		return names.iterator();
	}

}
