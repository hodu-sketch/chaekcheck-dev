package com.chaekcheck.collection.internal.importer;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

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

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.library.LibraryCatalog;

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

	/**
	 * @param sqlitePath the collection DB (read-only)
	 * @param ttlPath folder with the unzipped national bibliography TTL files; empty skips the ISBN lookup
	 * @param dryRun read and group only, log the counts, write nothing
	 * @param placeholderDateLibraries collection ids whose dates before 1950 are placeholders (ADR-0054)
	 */
	@ConfigurationProperties("chaekcheck.collection.import")
	record Settings(Path sqlitePath, Path ttlPath, boolean dryRun, Set<Long> placeholderDateLibraries) {

		Settings {
			placeholderDateLibraries = placeholderDateLibraries == null ? Set.of() : Set.copyOf(placeholderDateLibraries);
		}

	}

	@Bean
	ApplicationRunner collectionImport(LibraryCatalog libraryCatalog, CatalogLoad catalogLoad, JdbcClient jdbc,
			Settings settings, ConfigurableApplicationContext context) {
		return (ApplicationArguments args) -> {
			if (settings.sqlitePath() == null) {
				throw new IllegalStateException("Set chaekcheck.collection.import.sqlite-path");
			}
			new CatalogImporter(libraryCatalog, new JdbcCollectionLinks(jdbc), catalogLoad, new ImportRecords(jdbc),
					settings.placeholderDateLibraries(), Clock.systemUTC())
				.run(settings.sqlitePath(), settings.ttlPath(), settings.dryRun());
			System.exit(SpringApplication.exit(context, () -> 0));
		};
	}

}
