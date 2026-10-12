package com.chaekcheck.collection.internal.libraryinsert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.NewEntry;
import com.chaekcheck.library.LibraryRegistry;
import com.chaekcheck.library.RegisteredLibrary;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;

/**
 * The library part of an export file (plan cycle 13, ADR-0086, ADR-0093): every succeeded library-insert run of the
 * PC, carried in {@code load_manifest.notes.libraryPart} so the production apply puts the same libraries, links and
 * run rows in before it compares the two databases. Runs and libraries already there are skipped, so an apply can
 * run again after a failure later on.
 */
public final class LibraryPart {

	private static final TypeReference<List<NewEntry>> ENTRIES = new TypeReference<>() {
	};

	/** One {@code collection.library_insert_run} row of status SUCCEEDED, as the file carries it. */
	public record Run(String reportId, Instant ranAt, String snapshot, int insertedCount, LocalDate joinSurveyedOn,
			JsonNode payload) {
	}

	/** What an apply did. */
	public record Applied(int runs, int skippedRuns, int libraries, int links) {
	}

	private LibraryPart() {
	}

	/** Every succeeded run of this database, oldest first. */
	public static List<Run> export(JdbcClient jdbc) {
		return jdbc.sql("""
				SELECT report_id, ran_at, snapshot, inserted_count, join_surveyed_on, payload::text
				FROM collection.library_insert_run WHERE status = 'SUCCEEDED' ORDER BY ran_at, id""")
			.query((rs, n) -> new Run(rs.getString(1), rs.getObject(2, OffsetDateTime.class).toInstant(),
					rs.getString(3), rs.getInt(4), rs.getObject(5, LocalDate.class),
					InsertReport.JSON.readTree(rs.getString(6))))
			.list();
	}

	/**
	 * Puts the runs in, in the caller's transaction, under the library part's lock: for each run not here yet (by
	 * report id) the libraries whose ids are not taken ({@link LibraryRegistry#add}), the links not here, and the run
	 * row with its original time. A library id that is here already must be the same library (name and address), and
	 * a link that is here already must point at the same library; otherwise this throws and the caller rolls back.
	 * @throws IllegalStateException when the payload is broken or this database holds another library or link
	 */
	public static Applied apply(List<Run> runs, LibraryRegistry registry, JdbcClient jdbc) {
		LibraryInsertCommand.lock(jdbc);
		if (runs == null || runs.isEmpty()) {
			return new Applied(0, 0, 0, 0);
		}
		Map<Long, RegisteredLibrary> here = new HashMap<>();
		registry.listAll().forEach(library -> here.put(library.id(), library));
		int applied = 0, skipped = 0, libraries = 0, links = 0;
		for (Run run : runs.stream().sorted(Comparator.comparing(Run::ranAt)).toList()) {
			boolean done = jdbc.sql("""
					SELECT count(*) > 0 FROM collection.library_insert_run
					WHERE report_id = :reportId AND status = 'SUCCEEDED'""")
				.param("reportId", run.reportId())
				.query(Boolean.class)
				.single();
			if (done) {
				skipped++;
				continue;
			}
			JsonNode payload = run.payload() == null ? null : run.payload().get("newLibraries");
			if (payload == null || !payload.isArray()) {
				throw new IllegalStateException("run " + run.reportId() + " has no newLibraries list");
			}
			List<NewEntry> entries = InsertReport.JSON.convertValue(payload, ENTRIES);
			List<com.chaekcheck.library.NewLibrary> missing = new java.util.ArrayList<>();
			for (NewEntry entry : entries) {
				RegisteredLibrary existing = here.get(entry.library().id());
				if (existing == null) {
					missing.add(entry.library());
				}
				else if (!existing.name().equals(entry.library().name())
						|| !Objects.equals(existing.baseUrl(), entry.library().baseUrl())) {
					throw new IllegalStateException("library " + existing.id() + " is " + existing.name() + " "
							+ existing.baseUrl() + " here, the file has " + entry.library().name() + " "
							+ entry.library().baseUrl());
				}
			}
			registry.add(missing);
			registry.listAll().forEach(library -> here.put(library.id(), library));
			libraries += missing.size();
			for (NewEntry entry : entries) {
				Long linked = jdbc.sql("""
						SELECT library_id FROM collection.collection_link WHERE collection_library_id = :collectionId""")
					.param("collectionId", entry.collectionId())
					.query(Long.class)
					.optional()
					.orElse(null);
				if (linked == null) {
					links += jdbc.sql("""
							INSERT INTO collection.collection_link (collection_library_id, library_id)
							VALUES (:collectionId, :libraryId)""")
						.param("collectionId", entry.collectionId())
						.param("libraryId", entry.library().id())
						.update();
				}
				else if (linked != entry.library().id()) {
					throw new IllegalStateException("collection library " + entry.collectionId() + " is linked to "
							+ linked + " here, the file has " + entry.library().id());
				}
			}
			jdbc.sql("""
					INSERT INTO collection.library_insert_run
					  (report_id, ran_at, snapshot, inserted_count, join_surveyed_on, status, payload)
					VALUES (:reportId, :ranAt, :snapshot, :inserted, :surveyedOn, 'SUCCEEDED', CAST(:payload AS jsonb))""")
				.param("reportId", run.reportId())
				.param("ranAt", OffsetDateTime.ofInstant(run.ranAt(), java.time.ZoneOffset.UTC))
				.param("snapshot", run.snapshot())
				.param("inserted", run.insertedCount())
				.param("surveyedOn", run.joinSurveyedOn())
				.param("payload", InsertReport.JSON.writeValueAsString(run.payload()))
				.update();
			applied++;
		}
		return new Applied(applied, skipped, libraries, links);
	}

}
