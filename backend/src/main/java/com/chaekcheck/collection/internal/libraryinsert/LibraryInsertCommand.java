package com.chaekcheck.collection.internal.libraryinsert;

import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.collection.internal.libraryinsert.InsertPlan.NewEntry;
import com.chaekcheck.library.LibraryRegistry;

/**
 * The library-insert command (plan cycle 13 R2, ADR-0084): {@link #preview} writes a report and nothing else;
 * {@link #insert} recomputes it, checks it against the report given, and puts the new libraries in with their
 * eligibility rules, links and one run row in one transaction (handoff step 5).
 */
public class LibraryInsertCommand {

	/**
	 * {@code pg_advisory_xact_lock} key of the library part, shared with the production apply (ADR-0086): two inserts
	 * never compute the same ids at once. The text "chaekcheck-library-insert" has no meaning to PostgreSQL.
	 */
	public static final long LOCK_KEY = 0x63686b4c6962496eL;

	private static final Logger log = LoggerFactory.getLogger(LibraryInsertCommand.class);

	private static final int FAILURE_DETAIL = 1000;

	/** Exit code 0 and what was done. */
	record Outcome(int exitCode, InsertPlan plan, String reportId, Path reportFile, int inserted) {
	}

	private final LibraryRegistry registry;

	private final JdbcClient jdbc;

	private final TransactionTemplate transactions;

	private final Clock clock;

	LibraryInsertCommand(LibraryRegistry registry, JdbcClient jdbc, PlatformTransactionManager transactionManager,
			Clock clock) {
		this.registry = registry;
		this.jdbc = jdbc;
		this.transactions = new TransactionTemplate(transactionManager);
		this.clock = clock;
	}

	/** Writes the report; exit 2 or 3 when suspects are left, else 0. Writes nothing to the database. */
	Outcome preview(Path sqlite, Path rulesDir, Path reportDir) {
		Computed computed = compute(sqlite, rulesDir);
		Path file = computed.report().write(reportDir, clock.instant());
		InsertPlan plan = computed.plan();
		log.info("library-insert preview: report {} ({})", computed.report().id(), file);
		log.info("new {}, changed {}, gone {} (+{} in gone_known.tsv), suspects {}{}", plan.newLibraries().size(),
				plan.changed().size(), plan.gone().size(), plan.goneKnown().size(), plan.suspects().size(),
				plan.newLibraries().isEmpty() ? " - no library to insert (넣을 도서관 없음)" : "");
		return new Outcome(suspectExit(plan), plan, computed.report().id(), file, 0);
	}

	/**
	 * Puts the libraries of report {@code reportId} in. Exit 4 when the report is missing or differs (nothing
	 * written), 2 or 3 when suspects are left, 0 with nothing to do when there is no new library.
	 * @throws DataAccessException when the database refuses the rows: everything is rolled back, and a FAILED run row
	 * is written in a transaction of its own
	 */
	Outcome insert(Path sqlite, Path rulesDir, Path reportDir, String reportId) {
		Computed computed = compute(sqlite, rulesDir);
		computed.report().requireSameAs(reportDir, reportId);
		InsertPlan plan = computed.plan();
		int exit = suspectExit(plan);
		if (exit != 0) {
			log.warn("library-insert: report {} has suspects left; nothing inserted", reportId);
			return new Outcome(exit, plan, reportId, InsertReport.file(reportDir, reportId), 0);
		}
		if (plan.newLibraries().isEmpty()) {
			log.info("library-insert: no library to insert (넣을 도서관 없음)");
			return new Outcome(0, plan, reportId, InsertReport.file(reportDir, reportId), 0);
		}
		String payload = InsertReport.JSON.writeValueAsString(Map.of("newLibraries", plan.newLibraries()));
		String snapshot = sqlite.getFileName().toString();
		try {
			transactions.executeWithoutResult(status -> {
				lock(jdbc);
				// another insert may have taken the ids since the computation
				if (registry.nextId() != plan.firstId()) {
					throw new LibraryInsertException(LibraryInsertException.REPORT, "the next library id is "
							+ registry.nextId() + " now, the report starts at " + plan.firstId() + "; preview again");
				}
				registry.add(plan.newLibraries().stream().map(NewEntry::library).toList());
				for (NewEntry entry : plan.newLibraries()) {
					jdbc.sql("""
							INSERT INTO collection.collection_link (collection_library_id, library_id)
							VALUES (:collectionId, :libraryId)""")
						.param("collectionId", entry.collectionId())
						.param("libraryId", entry.library().id())
						.update();
				}
				run(reportId, snapshot, plan.newLibraries().size(), computed.rules(), "SUCCEEDED", null, payload);
			});
		}
		catch (DataAccessException ex) {
			String detail = String.valueOf(ex.getMostSpecificCause().getMessage());
			transactions.executeWithoutResult(status -> run(reportId, snapshot, 0, computed.rules(), "FAILED",
					detail.substring(0, Math.min(detail.length(), FAILURE_DETAIL)), payload));
			log.error("library-insert: report {} not inserted, rolled back: {}", reportId, detail);
			throw ex;
		}
		log.info("library-insert: report {} inserted {} libraries ({}..{})", reportId, plan.newLibraries().size(),
				plan.firstId(), plan.firstId() + plan.newLibraries().size() - 1);
		return new Outcome(0, plan, reportId, InsertReport.file(reportDir, reportId), plan.newLibraries().size());
	}

	/** Takes the library part's lock until the end of the transaction. */
	public static void lock(JdbcClient jdbc) {
		jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", LOCK_KEY).query((rs, n) -> 1).list();
	}

	private void run(String reportId, String snapshot, int inserted, RuleSet rules, String status, String detail,
			String payload) {
		jdbc.sql("""
				INSERT INTO collection.library_insert_run
				  (report_id, snapshot, inserted_count, join_surveyed_on, status, failure_detail, payload)
				VALUES (:reportId, :snapshot, :inserted, :surveyedOn, :status, :detail, CAST(:payload AS jsonb))""")
			.param("reportId", reportId)
			.param("snapshot", snapshot)
			.param("inserted", inserted)
			.param("surveyedOn", rules.joinSurveyedOn())
			.param("status", status)
			.param("detail", detail)
			.param("payload", payload)
			.update();
	}

	private static int suspectExit(InsertPlan plan) {
		if (plan.hasSameHost()) {
			return LibraryInsertException.SAME_HOST;
		}
		return plan.hasOrderNo() ? LibraryInsertException.ORDER_NO : 0;
	}

	private record Computed(RuleSet rules, InsertPlan plan, InsertReport report) {
	}

	private Computed compute(Path sqlite, Path rulesDir) {
		RuleSet rules = RuleSet.read(rulesDir);
		Map<Long, Long> links = new HashMap<>();
		jdbc.sql("SELECT collection_library_id, library_id FROM collection.collection_link")
			.query(rs -> {
				links.put(rs.getLong(1), rs.getLong(2));
			});
		try (CollectedLibraries copy = CollectedLibraries.open(sqlite)) {
			InsertPlan plan = new LibraryInsertPlanner(rules).plan(copy.libraries(), registry.listAll(),
					new LinkedHashMap<>(links), registry.nextId(), copy);
			return new Computed(rules, plan, InsertReport.of(plan, rules, sqlite.getFileName().toString()));
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Reading the collection DB copy failed: " + sqlite, ex);
		}
	}

	/** For tests: the libraries of a plan as one list. */
	static List<Long> ids(InsertPlan plan) {
		return plan.newLibraries().stream().map(entry -> entry.library().id()).toList();
	}

}
