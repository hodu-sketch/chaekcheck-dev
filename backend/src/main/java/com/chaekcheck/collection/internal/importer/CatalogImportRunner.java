package com.chaekcheck.collection.internal.importer;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRegistry;
import com.chaekcheck.collection.internal.booktexts.BookProfilesImport;
import com.chaekcheck.collection.internal.booktexts.BookTextsImport;
import com.chaekcheck.collection.internal.booktexts.ExportIds;
import com.chaekcheck.collection.internal.foryou.NeighboursImport;
import com.chaekcheck.collection.internal.foryou.TagDictionaryImport;
import com.chaekcheck.foryou.ForYouTransfer;
import com.chaekcheck.picks.CuratedListTransfer;
import com.chaekcheck.picks.ListedBooks;
import com.sun.management.OperatingSystemMXBean;

/**
 * Runs one catalog import and exits (profile {@code collection-import} only, ADR-0031, ADR-0060). Never active in
 * other profiles or tests. Paths come from arguments or the environment; no path on the developer's PC is
 * committed (docs/maintenance/3-import.md).
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=collection-import
 *   --chaekcheck.collection.import.sqlite-path=../../chaekcheck-data/snapshots/ebook-catalog-2026-10-05-0745.sqlite
 *   --chaekcheck.collection.import.ttl-path=../data/nlk/ttl'
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@Profile("collection-import")
@EnableConfigurationProperties(CatalogImportRunner.Settings.class)
class CatalogImportRunner {

	private static final Logger log = LoggerFactory.getLogger(CatalogImportRunner.class);

	/**
	 * The mode values (cycle 11, ADR-0078 [확정]; {@code lists} user 2026-10-19; {@code book-texts} cycle 24,
	 * ADR-0151; {@code neighbours} and {@code tag-dictionary} cycle 23 step 3-3, ADR-0149; {@code book-profiles} cycle
	 * 26, ADR-0165).
	 */
	static final Set<String> MODES = Set.of("in-place", "export", "apply", "finish", "discard", "lists", "book-texts",
			"neighbours", "tag-dictionary", "book-profiles");

	/** The modes whose file names the catalog export its book ids are from, so {@code force} applies (cycle 26). */
	static final Set<String> EXPORT_ID_MODES = Set.of("neighbours", "book-texts", "book-profiles");

	/** The day {@code neighbours} writes as {@code computed_on} is the service's day. */
	private static final ZoneId SERVICE_ZONE = ZoneId.of("Asia/Seoul");

	/** The ways to group books (cycle 11, ADR-0079 [확정]). */
	static final Set<String> GROUPINGS = Set.of("objects", "compact", "sql");

	/**
	 * @param sqlitePath the collection DB (read-only); needed by {@code in-place} and {@code export}
	 * @param ttlPath folder with the unzipped national bibliography TTL files; empty skips the ISBN lookup
	 * @param dryRun read and group only, log the counts, write nothing
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders (ADR-0054)
	 * @param mode {@code in-place} (default: load and swap in this database), {@code export} (load into new tables
	 * and write them into {@code file}), {@code apply} (load {@code file} here), {@code finish} (swap in the new tables
	 * waiting for {@code file}), {@code discard} (drop the new tables) or {@code lists} (only the recommended lists of
	 * {@code listsDir}, matched to the catalog in this database; user 2026-10-19). docs/maintenance/3-import.md 13, 14.
	 * The text modes {@code book-texts} (cycle 24), {@code neighbours} and {@code tag-dictionary} (cycle 23) and
	 * {@code book-profiles} (cycle 26) touch no book list table (since cycle 26 they write the catalog's book
	 * descriptions)
	 * @param file the export file of {@code export}, {@code apply} and {@code finish}
	 * @param grouping how the books are grouped (ADR-0079): {@code compact} (default since 2026-10-12, user decision
	 * after the cycle 11 measurement), {@code objects} (one object per unit; called {@code current} until 2026-10-12) or
	 * {@code sql}; all three give the same books and ids
	 * @param dryRunOutput folder a dry run writes {@code books.tsv.gz} and {@code rows.tsv.gz} into, for
	 * {@code scripts/catalog/compare_grouping.py}; empty writes only the counts
	 * @param excludeAddedLaterLibraries leave out the libraries added later in cycle 07 (collection id from
	 * {@link CollectedCatalog#FIRST_COLLECTED_ONLY_ID}); their rows are skipped as
	 * {@code library_excluded_added_later}. Dry run only (cycle 12): a real import would drop about 2,370 libraries
	 * from the service. Named {@code exclude-candidate-libraries} until cycle 18
	 * @param rulesDir the rules folder ({@code scripts/library/rules}, cycle 19 {@link ImportRules}); read by the modes
	 * that read a collection DB ({@code in-place}, dry run, {@code export}), before the DB is opened
	 * @param storedBibliography step ③ from the stored national bibliography records without the TTL files (cycle 19:
	 * the TTL files were lost on 2026-10-15, user choice 2026-10-16); only without {@code ttl-path}
	 * @param listsDir the recommended lists folder ({@code scripts/crawl/lists}, cycle 21 ADR-0128); {@code export}
	 * and a real {@code in-place} stop before reading the collection DB when it or a list file is missing; {@code lists}
	 * reads only it
	 * @param bookTextsFile the TSV of mode {@code book-texts} (cycle 24, ADR-0151): the AI search's lines, put in with
	 * every listed book of this database; docs/maintenance/6-aisearch.md. Since cycle 23 step 3-3 (ADR-0149) also the
	 * for-you tab's blurbs, tags and cover file keys; docs/maintenance/7-foryou.md 2
	 * @param neighboursFile the file of mode {@code neighbours} (cycle 23, ADR-0149): {@code # export_id=…}, then
	 * {@code book_id\tn1,n2,…}
	 * @param neighboursLoad mode {@code neighbours}' {@code full} (swap the whole table in) or {@code add} (upsert); no
	 * default, so a full swap is never made by leaving it out
	 * @param tagDictionaryFile the TSV of mode {@code tag-dictionary} (cycle 23, ADR-0149 decision #9)
	 * @param force modes {@code neighbours}, {@code book-texts} and {@code book-profiles} (since cycle 26): put the file
	 * in although its {@code export_id} is not the one of this database's latest succeeded catalog import
	 * @param bookTextsTopBooks how many of the books held by the most libraries mode {@code book-texts} adds to the
	 * listed books (user 2026-10-25: 30,000; 0 = listed books only)
	 * @param reloadUrls where to send {@code POST /internal/aisearch/books/reload} after an import that changed the AI
	 *     search's books or the catalog (cycle 29, ADR-0174); empty: none is sent
	 * @param bookProfilesFile the file of mode {@code book-profiles} (cycle 26, ADR-0165): {@code # export_id=…}, then
	 * the PC's nine-cell profile rows
	 */
	@ConfigurationProperties("chaekcheck.collection.import")
	record Settings(Path sqlitePath, Path ttlPath, boolean dryRun, Set<Long> placeholderDateLibraries, String mode,
			Path file, String grouping, Path dryRunOutput, boolean excludeAddedLaterLibraries, Path rulesDir,
			boolean storedBibliography, Path listsDir, Path bookTextsFile, Path neighboursFile, String neighboursLoad,
			Path tagDictionaryFile, boolean force, Integer bookTextsTopBooks, Path bookProfilesFile,
			java.util.List<java.net.URI> reloadUrls) {

		@org.springframework.boot.context.properties.bind.ConstructorBinding
		Settings {
			reloadUrls = reloadUrls == null ? java.util.List.of() : java.util.List.copyOf(reloadUrls);
			placeholderDateLibraries = placeholderDateLibraries == null ? Set.of() : Set.copyOf(placeholderDateLibraries);
			mode = mode == null ? "in-place" : mode;
			grouping = grouping == null ? "compact" : grouping;
			rulesDir = rulesDir == null ? Path.of("../scripts/library/rules") : rulesDir;
			listsDir = listsDir == null ? Path.of("../scripts/crawl/lists") : listsDir;
		}

		/** As the canonical one with no reload URL (the tests' settings). */
		Settings(Path sqlitePath, Path ttlPath, boolean dryRun, Set<Long> placeholderDateLibraries, String mode,
				Path file, String grouping, Path dryRunOutput, boolean excludeAddedLaterLibraries, Path rulesDir,
				boolean storedBibliography, Path listsDir, Path bookTextsFile, Path neighboursFile, String neighboursLoad,
				Path tagDictionaryFile, boolean force, Integer bookTextsTopBooks, Path bookProfilesFile) {
			this(sqlitePath, ttlPath, dryRun, placeholderDateLibraries, mode, file, grouping, dryRunOutput,
					excludeAddedLaterLibraries, rulesDir, storedBibliography, listsDir, bookTextsFile, neighboursFile,
					neighboursLoad, tagDictionaryFile, force, bookTextsTopBooks, bookProfilesFile, null);
		}

		/** Whether the mode reads a collection DB, and so the rules files. */
		boolean readsCollection() {
			return mode.equals("in-place") || mode.equals("export");
		}

		/** Refuses wrong values before anything runs. */
		void check() {
			if (!MODES.contains(mode)) {
				throw new IllegalStateException("chaekcheck.collection.import.mode must be one of " + MODES + ": " + mode);
			}
			if (!GROUPINGS.contains(grouping)) {
				throw new IllegalStateException(
						"chaekcheck.collection.import.grouping must be one of " + GROUPINGS + ": " + grouping);
			}
			if ((mode.equals("in-place") || mode.equals("export")) && sqlitePath == null) {
				throw new IllegalStateException("Set chaekcheck.collection.import.sqlite-path");
			}
			if ((mode.equals("export") || mode.equals("apply") || mode.equals("finish")) && file == null) {
				throw new IllegalStateException("Set chaekcheck.collection.import.file for mode " + mode);
			}
			if (mode.equals("book-texts") && bookTextsFile == null) {
				throw new IllegalStateException("Set chaekcheck.collection.import.book-texts-file for mode book-texts");
			}
			if (mode.equals("book-texts") && (bookTextsTopBooks == null || bookTextsTopBooks < 0)) {
				throw new IllegalStateException(
						"Set chaekcheck.collection.import.book-texts-top-books (0 or more) for mode book-texts");
			}
			if (mode.equals("neighbours")) {
				if (neighboursFile == null) {
					throw new IllegalStateException("Set chaekcheck.collection.import.neighbours-file for mode neighbours");
				}
				if (neighboursLoad == null || !NeighboursImport.LOADS.contains(neighboursLoad)) {
					throw new IllegalStateException("Set chaekcheck.collection.import.neighbours-load to one of "
							+ NeighboursImport.LOADS + " for mode neighbours: " + neighboursLoad);
				}
			}
			if (mode.equals("tag-dictionary") && tagDictionaryFile == null) {
				throw new IllegalStateException(
						"Set chaekcheck.collection.import.tag-dictionary-file for mode tag-dictionary");
			}
			if (mode.equals("book-profiles") && bookProfilesFile == null) {
				throw new IllegalStateException(
						"Set chaekcheck.collection.import.book-profiles-file for mode book-profiles");
			}
			if (force && !EXPORT_ID_MODES.contains(mode)) {
				throw new IllegalStateException(
						"force is a setting of modes " + EXPORT_ID_MODES + " only; mode " + mode + " has none");
			}
			if (storedBibliography && ttlPath != null) {
				throw new IllegalStateException("Set either ttl-path or stored-bibliography, not both");
			}
			if (dryRun && !mode.equals("in-place")) {
				throw new IllegalStateException("dry-run is a read of mode in-place; mode " + mode + " has none");
			}
			if (excludeAddedLaterLibraries && !dryRun) {
				throw new IllegalStateException("exclude-added-later-libraries is a dry-run-only setting: a real import"
						+ " would leave about 2,370 libraries out of the service; set dry-run=true");
			}
		}

	}

	@Bean
	ApplicationRunner collectionImport(LibraryCatalog libraryCatalog, LibraryRegistry libraryRegistry,
			CatalogLoad catalogLoad, JdbcClient jdbc, DataSource dataSource,
			PlatformTransactionManager transactionManager, Settings settings, ConfigurableApplicationContext context,
			CuratedListTransfer curatedLists, BookLookup bookLookup, ListedBooks listedBooks,
			BookTextsTransfer bookTexts, ForYouTransfer forYou, MostHeldBooks mostHeldBooks) {
		return (ApplicationArguments args) -> {
			settings.check();
			log.info("collection import: mode {}, grouping {}", settings.mode(), settings.grouping());
			// the rules files first: a missing or wrong file stops before the collection DB is read (cycle 19)
			ImportRules rules = settings.readsCollection() ? ImportRules.read(settings.rulesDir()) : ImportRules.NONE;
			ImportRecords records = new ImportRecords(jdbc);
			CatalogImporter importer = new CatalogImporter(libraryCatalog, new JdbcCollectionLinks(jdbc), catalogLoad,
					records, new NationalBibliographyStore(dataSource), settings.placeholderDateLibraries(),
					Clock.systemUTC())
				.grouping(groupers(settings.grouping(), dataSource))
				.dryRunOutput(settings.dryRunOutput())
				.excludeAddedLaterLibraries(settings.excludeAddedLaterLibraries())
				.rules(rules)
				.storedBibliography(settings.storedBibliography());
			ImportTransfer transfer = new ImportTransfer(importer, catalogLoad, libraryCatalog, libraryRegistry, jdbc,
					transactionManager, records, Clock.systemUTC(), curatedLists, settings.listsDir());
			SearchBooksReloadRequest reload = new SearchBooksReloadRequest(settings.reloadUrls());
			switch (settings.mode()) {
				case "export" -> transfer.export(settings.sqlitePath(), settings.ttlPath(), settings.file(),
						settings.grouping());
				case "apply" -> {
					transfer.apply(settings.file());
					reload.run();
				}
				case "finish" -> {
					transfer.finish(settings.file());
					reload.run();
				}
				case "discard" -> log.info("discarded: {}", transfer.discard());
				case "lists" -> transfer.listsOnly(bookLookup);
				case "book-texts" -> new BookTextsImport(listedBooks, mostHeldBooks, settings.bookTextsTopBooks(), bookTexts,
						reload, () -> ExportIds.latest(jdbc), new TransactionTemplate(transactionManager))
					.run(settings.bookTextsFile(), settings.force());
				case "book-profiles" -> new BookProfilesImport(bookTexts, () -> ExportIds.latest(jdbc)).run(settings.bookProfilesFile(),
						settings.force());
				case "neighbours" -> new NeighboursImport(jdbc, forYou, Clock.system(SERVICE_ZONE))
					.run(settings.neighboursFile(), settings.neighboursLoad(), settings.force());
				case "tag-dictionary" -> new TagDictionaryImport(bookTexts).run(settings.tagDictionaryFile());
				default -> {
					if (!settings.dryRun()) {
						transfer.requireNoWaitingExport();
						transfer.checkLists();
					}
					importer.run(settings.sqlitePath(), settings.ttlPath(), settings.dryRun());
					if (!settings.dryRun()) {
						transfer.replaceListsInPlace(bookLookup);
						reload.run();
					}
				}
			}
			// cycle 11 measurement (ADR-0079): the Java side's CPU, next to the database container's cpu.stat
			if (ManagementFactory.getOperatingSystemMXBean() instanceof OperatingSystemMXBean os) {
				log.info("process cpu {} s, uptime {} s", os.getProcessCpuTime() / 1_000_000_000.0,
						ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
			}
			System.exit(SpringApplication.exit(context, () -> 0));
		};
	}

	/** The grouping of {@code chaekcheck.collection.import.grouping} (ADR-0079). */
	static Supplier<BookGrouper> groupers(String grouping, DataSource dataSource) {
		return switch (grouping) {
			case "objects" -> BookGrouping::new;
			case "compact" -> CompactGrouping::new;
			case "sql" -> () -> new SqlGrouping(dataSource);
			default -> throw new IllegalStateException("grouping " + grouping + " is not built yet");
		};
	}

}
