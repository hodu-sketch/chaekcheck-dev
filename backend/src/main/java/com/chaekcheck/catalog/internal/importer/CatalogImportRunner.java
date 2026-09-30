package com.chaekcheck.catalog.internal.importer;

import java.nio.file.Path;
import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import com.chaekcheck.library.LibraryCatalog;

/**
 * Runs one catalog import and exits (profile {@code catalog-import} only, ADR-0031). Never active in other profiles
 * or tests. Paths come from arguments or the environment; no path on the developer's PC is committed.
 * <pre>
 * ./gradlew bootRun --args='--spring.profiles.active=catalog-import
 *   --chaekcheck.catalog.import.sqlite-path=../data/ebook-catalog.sqlite
 *   --chaekcheck.catalog.import.ttl-path=../data/nlk/ttl'
 * </pre>
 */
@Configuration(proxyBeanMethods = false)
@Profile("catalog-import")
@EnableConfigurationProperties(CatalogImportRunner.Settings.class)
class CatalogImportRunner {

	/**
	 * @param sqlitePath the collected SQLite (read-only)
	 * @param ttlPath folder with the unzipped national bibliography TTL files; empty skips the ISBN lookup
	 * @param dryRun read and group only, log the counts, write nothing
	 */
	@ConfigurationProperties("chaekcheck.catalog.import")
	record Settings(Path sqlitePath, Path ttlPath, boolean dryRun) {
	}

	@Bean
	ApplicationRunner catalogImport(LibraryCatalog libraryCatalog, DataSource dataSource, Settings settings,
			ConfigurableApplicationContext context) {
		return (ApplicationArguments args) -> {
			if (settings.sqlitePath() == null) {
				throw new IllegalStateException("Set chaekcheck.catalog.import.sqlite-path");
			}
			new CatalogImporter(libraryCatalog, dataSource, Clock.systemUTC()).run(settings.sqlitePath(),
					settings.ttlPath(), settings.dryRun());
			System.exit(SpringApplication.exit(context, () -> 0));
		};
	}

}
