package com.chaekcheck.collection.internal.foryou;

import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.foryou.ForYouTransfer;

/**
 * The {@code neighbours} import mode (cycle 23 step 3-3, ADR-0149): the crawl PC's book neighbours into {@code foryou}
 * through {@link ForYouTransfer}. {@code full} swaps the whole table in (COPY into a new table, then a short swap;
 * a failure leaves the old table), {@code add} upserts the file's books in one transaction. The book ids are those of
 * the catalog the PC computed on, so the file's {@code export_id} must be the one of this database's latest succeeded
 * catalog import ({@code collection.catalog_import.export_id}, set by {@code apply} and {@code finish}; an
 * {@code in-place} import has none): otherwise it is refused before anything is read or written, unless
 * {@code force}. {@code computed_on} is the day of this run (Asia/Seoul): the file carries no date.
 */
public final class NeighboursImport {

	private static final Logger log = LoggerFactory.getLogger(NeighboursImport.class);

	/** The load values (design-delta "넣는 명령"). */
	public static final Set<String> LOADS = Set.of("full", "add");

	private final JdbcClient jdbc;

	private final ForYouTransfer forYou;

	private final Clock clock;

	public NeighboursImport(JdbcClient jdbc, ForYouTransfer forYou, Clock clock) {
		this.jdbc = jdbc;
		this.forYou = forYou;
		this.clock = clock;
	}

	/**
	 * @param rows rows written
	 * @param forced whether the export id differed and {@code force} let it through
	 */
	public record Report(String load, UUID fileExportId, UUID databaseExportId, long rows, boolean forced) {
	}

	/**
	 * @param load {@code full} or {@code add}
	 * @throws NeighboursFile.RefusedException when the file is refused (export id, rows); nothing is written
	 * @throws IllegalStateException when the load fails; the table before stays
	 */
	public Report run(Path file, String load, boolean force) {
		if (!LOADS.contains(load)) {
			throw new IllegalStateException("neighbours-load must be one of " + LOADS + ": " + load);
		}
		UUID fileExportId = NeighboursFile.exportId(file);
		UUID databaseExportId = latestExportId().orElse(null);
		boolean differs = !fileExportId.equals(databaseExportId);
		if (differs && !force) {
			throw new NeighboursFile.RefusedException("The neighbours file is for export " + fileExportId
					+ " but this database's latest succeeded catalog import has export " + databaseExportId
					+ ": its book ids may differ, nothing put in. Make the file again on this catalog, or set force=true"
					+ " when the ids are known to be the same");
		}
		if (differs) {
			log.warn("neighbours: the file's export {} is not this database's {}; put in anyway (force)", fileExportId,
					databaseExportId);
		}
		long checked = NeighboursFile.check(file);
		LocalDate computedOn = LocalDate.now(clock);
		long written = NeighboursFile.load(file, rows -> load.equals("full")
				? forYou.replaceAllNeighbours(rows, computedOn) : forYou.upsertNeighbours(rows, computedOn));
		if (written != checked) {
			// the file changed between the check and the load
			log.warn("neighbours: {} rows checked but {} written", checked, written);
		}
		Report report = new Report(load, fileExportId, databaseExportId, written, differs);
		log.info("neighbours {}: rows {}, export {}, computed_on {}{}", load, written, fileExportId, computedOn,
				differs ? " (forced over " + databaseExportId + ")" : "");
		return report;
	}

	/** The export id of the latest succeeded catalog import; empty when it had none (in-place) or there is none. */
	Optional<UUID> latestExportId() {
		return jdbc.sql("""
				SELECT export_id FROM collection.catalog_import WHERE status = 'SUCCEEDED'
				ORDER BY imported_at DESC, id DESC LIMIT 1""")
			.query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
			.optional()
			.flatMap(id -> id);
	}

}
