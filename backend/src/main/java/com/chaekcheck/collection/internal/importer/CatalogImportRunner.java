package com.chaekcheck.collection.internal.importer;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Clock;
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

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRegistry;
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

	/** The mode values (cycle 11, ADR-0078 [확정]). */
	static final Set<String> MODES = Set.of("in-place", "export", "apply", "finish", "discard");

	/** The ways to group books (cycle 11, ADR-0079 [확정]). */
	static final Set<String> GROUPINGS = Set.of("objects", "compact", "sql");

	/**
	 * @param sqlitePath the collection DB (read-only); needed by {@code in-place} and {@code export}
	 * @param ttlPath folder with the unzipped national bibliography TTL files; empty skips the ISBN lookup
	 * @param dryRun read and group only, log the counts, write nothing
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders (ADR-0054)
	 * @param mode {@code in-place} (default: load and swap in this database), {@code export} (load into new tables
	 * and write them into {@code file}), {@code apply} (load {@code file} here), {@code finish} (swap in the new tables
	 * waiting for {@code file}) or {@code discard} (drop the new tables). docs/maintenance/3-import.md 13
	 * @param file the export file of {@code export}, {@code apply} and {@code finish}
	 * @param grouping how the books are grouped (ADR-0079): {@code compact} (default since 2026-10-12, user decision
	 * after the cycle 11 measurement), {@code objects} (one object per unit; called {@code current} until 2026-10-12) or
	 * {@code sql}; all three give the same books and ids
	 * @param dryRunOutput folder a dry run writes {@code books.tsv.gz} and {@code rows.tsv.gz} into, for
	 * {@code scripts/catalog/compare_grouping.py}; empty writes only the counts
	 * @param excludeCandidateLibraries leave out the cycle 07 candidate libraries (collection id from
	 * {@link CollectedCatalog#FIRST_CANDIDATE_ID}); their rows are skipped as {@code library_excluded_candidate}.
	 * Dry run only (cycle 12): a real import would drop about 2,394 libraries from the service
	 */
	@ConfigurationProperties("chaekcheck.collection.import")
	record Settings(Path sqlitePath, Path ttlPath, boolean dryRun, Set<Long> placeholderDateLibraries, String mode,
			Path file, String grouping, Path dryRunOutput, boolean excludeCandidateLibraries) {

		Settings {
			placeholderDateLibraries = placeholderDateLibraries == null ? Set.of() : Set.copyOf(placeholderDateLibraries);
			mode = mode == null ? "in-place" : mode;
			grouping = grouping == null ? "compact" : grouping;
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
			if (dryRun && !mode.equals("in-place")) {
				throw new IllegalStateException("dry-run is a read of mode in-place; mode " + mode + " has none");
			}
			if (excludeCandidateLibraries && !dryRun) {
				throw new IllegalStateException("exclude-candidate-libraries is a dry-run-only setting: a real import"
						+ " would leave about 2,394 candidate libraries out of the service; set dry-run=true");
			}
		}

	}

	@Bean
	ApplicationRunner collectionImport(LibraryCatalog libraryCatalog, LibraryRegistry libraryRegistry,
			CatalogLoad catalogLoad, JdbcClient jdbc, DataSource dataSource,
			PlatformTransactionManager transactionManager, Settings settings, ConfigurableApplicationContext context) {
		return (ApplicationArguments args) -> {
			settings.check();
			log.info("collection import: mode {}, grouping {}", settings.mode(), settings.grouping());
			ImportRecords records = new ImportRecords(jdbc);
			CatalogImporter importer = new CatalogImporter(libraryCatalog, new JdbcCollectionLinks(jdbc), catalogLoad,
					records, new NationalBibliographyStore(dataSource), settings.placeholderDateLibraries(),
					Clock.systemUTC())
				.grouping(groupers(settings.grouping(), dataSource))
				.dryRunOutput(settings.dryRunOutput())
				.excludeCandidateLibraries(settings.excludeCandidateLibraries());
			ImportTransfer transfer = new ImportTransfer(importer, catalogLoad, libraryCatalog, libraryRegistry, jdbc,
					transactionManager, records, Clock.systemUTC());
			switch (settings.mode()) {
				case "export" -> transfer.export(settings.sqlitePath(), settings.ttlPath(), settings.file(),
						settings.grouping());
				case "apply" -> transfer.apply(settings.file());
				case "finish" -> transfer.finish(settings.file());
				case "discard" -> log.info("discarded: {}", transfer.discard());
				default -> {
					if (!settings.dryRun()) {
						transfer.requireNoWaitingExport();
					}
					importer.run(settings.sqlitePath(), settings.ttlPath(), settings.dryRun());
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
