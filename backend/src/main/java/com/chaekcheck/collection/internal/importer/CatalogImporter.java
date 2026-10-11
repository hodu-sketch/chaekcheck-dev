package com.chaekcheck.collection.internal.importer;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.CatalogLoad.NewBook;
import com.chaekcheck.catalog.CatalogLoad.NewHolding;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Libraries;
import com.chaekcheck.library.LibraryCatalog;

/**
 * One import of the collection DB (ADR-0031, ADR-0054, ADR-0060): every enabled service library through the links,
 * read in two passes so the rows never sit in memory together.
 * <ol>
 * <li>First pass: filter the rows and count their values per step-① group ({@link BookGrouper}).</li>
 * <li>Group: Kyobo ISBN, national bibliography ISBN, same text.</li>
 * <li>Write through {@link CatalogLoad}: the books (with {@code library_ids}), then a second pass that streams each
 * row as a holding of its book, then the library snapshots. {@code VACUUM (ANALYZE)} follows.</li>
 * </ol>
 */
class CatalogImporter {

	private static final Logger log = LoggerFactory.getLogger(CatalogImporter.class);

	/** Future dates are judged by the Korean calendar day (the sites are Korean). */
	private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

	private final LibraryCatalog libraryCatalog;

	private final CollectionLinks links;

	private final CatalogLoad catalogLoad;

	private final ImportRecords records;

	private final NationalBibliographyStore bibliography;

	private final Set<Long> placeholderDateLibraries;

	private final Clock clock;

	/**
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders (ADR-0054)
	 */
	CatalogImporter(LibraryCatalog libraryCatalog, CollectionLinks links, CatalogLoad catalogLoad,
			ImportRecords records, NationalBibliographyStore bibliography, Set<Long> placeholderDateLibraries,
			Clock clock) {
		this.libraryCatalog = libraryCatalog;
		this.links = links;
		this.catalogLoad = catalogLoad;
		this.records = records;
		this.bibliography = bibliography;
		this.placeholderDateLibraries = Set.copyOf(placeholderDateLibraries);
		this.clock = clock;
	}

	/** How the books are grouped (cycle 11, ADR-0079); {@code current} ({@link BookGrouping}) unless set. */
	private Supplier<BookGrouper> groupers = BookGrouping::new;

	/** Folder a dry run writes its books and rows into (cycle 11, {@link DryRunDump}); null writes nothing. */
	private Path dryRunOutput;

	CatalogImporter grouping(Supplier<BookGrouper> groupers) {
		this.groupers = groupers;
		return this;
	}

	CatalogImporter dryRunOutput(Path folder) {
		this.dryRunOutput = folder;
		return this;
	}

	/**
	 * @param ttlDirectory folder with the unzipped national bibliography {@code .ttl} files; null skips step ③
	 * @param dryRun read and group only; nothing is written to the database
	 */
	ImportReport run(Path sqlite, Path ttlDirectory, boolean dryRun) throws SQLException, IOException {
		return run(sqlite, ttlDirectory, dryRun, false);
	}

	/**
	 * The PC half of an export (cycle 11, ADR-0078): reads, groups and fills {@code catalog_next} with
	 * {@link CatalogLoad#prepare}, which stays for the export. No row is kept when it works; a failure is kept as
	 * {@code FAILED} like an in-place import's.
	 */
	ImportReport prepare(Path sqlite, Path ttlDirectory) throws SQLException, IOException {
		return run(sqlite, ttlDirectory, false, true);
	}

	private ImportReport run(Path sqlite, Path ttlDirectory, boolean dryRun, boolean prepareOnly)
			throws SQLException, IOException {
		ImportReport report = new ImportReport();
		report.source = sqlite.getFileName().toString();
		LocalDate today = LocalDate.now(clock.withZone(KOREA));
		String source = sqlite.getFileName().toString();
		try (CollectedCatalog collected = CollectedCatalog.open(sqlite); BookGrouper grouping = groupers.get()) {
			Instant start = clock.instant();
			Libraries libraries = collected.libraries(links.all(), libraryCatalog.enabledLibraryRefs(),
					placeholderDateLibraries, report);
			collected.scan(libraries, today, report, row -> grouping.add(row, report));
			report.steps.put("read", Duration.between(start, clock.instant()));
			report.noteHeap();
			log.info("read {} rows, skipped {}", report.rowsRead, report.skippedCount());

			start = clock.instant();
			grouping.groupByCodes(report);
			report.steps.put("group", Duration.between(start, clock.instant()));
			report.noteHeap();

			start = clock.instant();
			if (ttlDirectory != null) {
				Set<String> keys = grouping.bibliographyKeys();
				log.info("national bibliography lookup for {} keys", keys.size());
				Map<String, String> isbns = NationalBibliography.lookup(ttlDirectory, keys, bibliography, clock, report);
				grouping.attachBibliography(isbns, report);
			}
			else {
				report.booksAfterNationalBibliography = report.booksAfterKyoboIsbn;
			}
			// nb: reading the records (table or TTL files) and attaching; normalize: making their keys
			report.steps.put("nb", Duration.between(start, clock.instant()).minus(report.nationalBibliographyNormalize));
			report.steps.put("normalize", report.nationalBibliographyNormalize);
			start = clock.instant();
			grouping.mergeSameText(report);
			report.steps.put("same_text", Duration.between(start, clock.instant()));
			report.noteHeap();

			if (dryRun) {
				report.books = report.booksAfterSameText;
				if (dryRunOutput != null) {
					start = clock.instant();
					DryRunDump.write(dryRunOutput, grouping,
							rows -> collected.scan(libraries, today, null, rows::accept));
					report.steps.put("dump", Duration.between(start, clock.instant()));
				}
				log.info("{}", report);
				log.info("dry run, nothing written. Merged books with dates 2+ years apart (samples):\n  {}",
						String.join("\n  ", report.sameTextWideDateSamples));
				return report;
			}
			start = clock.instant();
			CatalogLoad.Result result;
			try {
				CatalogLoad.Loader loader = session -> {
					grouping.forEachBook(book -> session.addBook(new NewBook(book.title(),
							book.authorDisplay(), book.publisher(), book.titleNorm(), book.mainTitleNorm(),
							book.publisherNorm(), book.publishedOn(), book.isbn13(), book.contentType(), book.libraryIds(),
							book.contributors())));
					report.noteHeap();
					collected.scan(libraries, today, null, row -> session.addHolding(new NewHolding(
							grouping.bookOf(row), row.library().serviceId(), row.library().platformType(),
							row.itemKey(), row.detailUrl(), row.contentType(), row.title(), row.authorRaw(), row.publisher(),
							row.publishedRaw(), row.supplier(), row.altCode(), row.firstSeenAt())));
					libraries.imported().values().forEach(library -> {
						session.addLibrarySnapshot(library.serviceId(), library.collectedAt());
						report.collected(library.collectedAt());
					});
				};
				result = prepareOnly ? catalogLoad.prepare(source, loader) : catalogLoad.replaceAll(source, loader);
			}
			catch (RuntimeException ex) {
				// the previous catalog stays (ADR-0065); the failure is kept with the import history
				report.steps.put("load", Duration.between(start, clock.instant()));
				String reason = ex instanceof CatalogLoad.NotSwappedException notSwapped ? notSwapped.reason() : "error";
				records.insertFailed(source, clock.instant(), report, reason, ex.toString());
				log.error("collection import failed ({}), the previous catalog stays", reason, ex);
				throw ex;
			}
			report.books = result.books();
			report.contributors = result.contributors();
			report.holdings = result.holdings();
			report.keptBookIds = result.keptBookIds();
			report.newBookIds = result.newBookIds();
			report.droppedBookIds = result.droppedBookIds();
			report.steps.putAll(result.steps());
			report.steps.put("load", Duration.between(start, clock.instant()));
			report.noteHeap();
			if (!prepareOnly) {
				records.insert(source, clock.instant(), report);
			}
			log.info("{}", report);
			return report;
		}
	}

}
