package com.chaekcheck.collection.internal.libraryinsert;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

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
	 * row with its original time.
	 */
	public static Applied apply(List<Run> runs, LibraryRegistry registry, JdbcClient jdbc) {
		LibraryInsertCommand.lock(jdbc);
		if (runs == null || runs.isEmpty()) {
			return new Applied(0, 0, 0, 0);
		}
		Set<Long> ids = new HashSet<>(registry.listAll().stream().map(RegisteredLibrary::id).toList());
		int applied = 0, skipped = 0, libraries = 0, links = 0;
		for (Run run : runs.stream().sorted(Comparator.comparing(Run::ranAt)).toList()) {
			boolean here = jdbc.sql("""
					SELECT count(*) > 0 FROM collection.library_insert_run
					WHERE report_id = :reportId AND status = 'SUCCEEDED'""")
				.param("reportId", run.reportId())
				.query(Boolean.class)
				.single();
			if (here) {
				skipped++;
				continue;
			}
			List<NewEntry> entries = InsertReport.JSON.convertValue(run.payload().get("newLibraries"), ENTRIES);
			var missing = entries.stream().map(NewEntry::library).filter(library -> !ids.contains(library.id())).toList();
			registry.add(missing);
			missing.forEach(library -> ids.add(library.id()));
			libraries += missing.size();
			for (NewEntry entry : entries) {
				links += jdbc.sql("""
						INSERT INTO collection.collection_link (collection_library_id, library_id)
						VALUES (:collectionId, :libraryId) ON CONFLICT (collection_library_id) DO NOTHING""")
					.param("collectionId", entry.collectionId())
					.param("libraryId", entry.library().id())
					.update();
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
