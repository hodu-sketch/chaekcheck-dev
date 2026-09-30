package com.chaekcheck.catalog.internal.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.internal.importer.BookGrouping.Book;
import com.chaekcheck.library.LibraryCatalog;

/**
 * One import of the collected catalog (ADR-0031, design-delta 흐름 3): read the collected SQLite, group rows into
 * books, look up national bibliography ISBNs, merge books with the same text, then replace the catalog tables with
 * COPY.
 */
public class CatalogImporter {

	private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);

	/** Future dates are judged by the Korean calendar day (the sites are Korean). */
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final LibraryCatalog libraryCatalog;

	private final DataSource dataSource;

	private final Clock clock;

	public CatalogImporter(LibraryCatalog libraryCatalog, DataSource dataSource, Clock clock) {
		this.libraryCatalog = libraryCatalog;
		this.dataSource = dataSource;
		this.clock = clock;
	}

	/**
	 * @param ttlDirectory folder with the unzipped national bibliography {@code .ttl} files; null skips step ③
	 */
	ImportReport run(Path sqlite, Path ttlDirectory) throws SQLException, IOException {
		return run(sqlite, ttlDirectory, false);
	}

	/** @param dryRun read and group only; nothing is written to the database */
	ImportReport run(Path sqlite, Path ttlDirectory, boolean dryRun) throws SQLException, IOException {
		ImportReport report = new ImportReport();
		LocalDate today = LocalDate.now(clock.withZone(KOREA));

		Instant start = clock.instant();
		CollectedCatalog.Contents contents = CollectedCatalog.read(sqlite, libraryCatalog.enabledLibraryRefs(), today,
				report);
		report.readTime = Duration.between(start, clock.instant());
		log.info("read {} rows, kept {}", report.rowsRead, contents.rows().size());

		start = clock.instant();
		BookGrouping grouping = BookGrouping.byCodes(contents.rows(), report);
		report.groupTime = Duration.between(start, clock.instant());

		start = clock.instant();
		if (ttlDirectory != null) {
			Set<String> keys = grouping.bibliographyKeys();
			log.info("national bibliography lookup for {} keys", keys.size());
			Map<String, String> isbns = NationalBibliography.lookup(ttlDirectory, keys, report);
			grouping.attachBibliography(isbns, report);
		}
		else {
			report.booksAfterNationalBibliography = report.booksAfterKyoboIsbn;
		}
		grouping.mergeSameText(report);
		report.bibliographyTime = Duration.between(start, clock.instant());

		if (dryRun) {
			report.books = report.booksAfterSameText;
			log.info("{}", report);
			log.info("dry run, nothing written. Merged books with dates 2+ years apart (samples):\n  {}",
					String.join("\n  ", report.sameTextWideDateSamples));
			return report;
		}
		start = clock.instant();
		List<Book> books = grouping.books();
		try (Connection connection = dataSource.getConnection()) {
			CatalogWriter.write(connection, sqlite.getFileName().toString(), clock.instant(), contents.libraries(),
					contents.rows(), books, report);
		}
		report.writeTime = Duration.between(start, clock.instant());
		log.info("{}", report);
		return report;
	}

}
