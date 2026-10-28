package com.chaekcheck.collection.internal.booktexts;

import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * The first row {@code # export_id=<uuid>} of the {@code book-texts} and {@code book-profiles} files (cycle 26,
 * ADR-0162): the {@code catalog_import.export_id} of the catalog whose book ids the crawl PC used. A file for another
 * catalog is refused before anything is written, unless {@code force}, as mode {@code neighbours} does.
 */
public final class ExportIds {

	private static final Logger log = LoggerFactory.getLogger(ExportIds.class);

	static final String PREFIX = "# export_id=";

	private ExportIds() {
	}

	/** @throws BookTextsFile.RefusedException when the row is not {@code # export_id=<uuid>} */
	static UUID parse(String firstRow) {
		String row = firstRow == null ? "" : firstRow.replaceFirst("^\\uFEFF", "").strip();
		if (!row.startsWith(PREFIX)) {
			throw new BookTextsFile.RefusedException("The first row must be " + PREFIX + "<the catalog's export id>");
		}
		try {
			return UUID.fromString(row.substring(PREFIX.length()).strip());
		}
		catch (IllegalArgumentException e) {
			throw new BookTextsFile.RefusedException("The first row's export id is not a UUID: " + row);
		}
	}

	/**
	 * Compares the file's export id with this database's latest succeeded catalog import.
	 * @param latest {@link #latest} of this database
	 * @param what the file's mode, for the messages
	 * @return whether they differ (and {@code force} let the file through)
	 * @throws BookTextsFile.RefusedException when they differ and {@code force} is off; nothing is written
	 */
	static boolean check(Optional<UUID> latest, UUID fileExportId, boolean force, String what) {
		UUID databaseExportId = latest.orElse(null);
		boolean differs = !fileExportId.equals(databaseExportId);
		if (differs && !force) {
			throw new BookTextsFile.RefusedException("The " + what + " file is for export " + fileExportId
					+ " but this database's latest succeeded catalog import has export " + databaseExportId
					+ ": its book ids may differ, nothing put in. Make the file again on this catalog, or set force=true"
					+ " when the ids are known to be the same");
		}
		if (differs) {
			log.warn("{}: the file's export {} is not this database's {}; put in anyway (force)", what, fileExportId,
					databaseExportId);
		}
		return differs;
	}

	/** The export id of the latest succeeded catalog import; empty when it had none (in-place) or there is none. */
	public static Optional<UUID> latest(JdbcClient jdbc) {
		return jdbc.sql("""
				SELECT export_id FROM collection.catalog_import WHERE status = 'SUCCEEDED'
				ORDER BY imported_at DESC, id DESC LIMIT 1""")
			.query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class)))
			.optional()
			.flatMap(id -> id);
	}

}
