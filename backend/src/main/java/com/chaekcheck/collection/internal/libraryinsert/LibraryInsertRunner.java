package com.chaekcheck.collection.internal.libraryinsert;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Set;

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

import com.chaekcheck.library.LibraryRegistry;

/**
 * Runs the library-insert command once and exits (profile {@code library-insert} only, plan cycle 13 ADR-0084; the
 * service keeps running). Paths are given as arguments, never committed, e.g.
 *
 * <pre>
 *   ./gradlew bootRun --args="--spring.profiles.active=library-insert
 *     --chaekcheck.collection.library-insert.mode=preview
 *     --chaekcheck.collection.library-insert.sqlite-path=../../chaekcheck-data/snapshots/ebook-catalog-2026-10-12-0135.sqlite
 *     --chaekcheck.collection.library-insert.rules-dir=../scripts/library/rules
 *     --chaekcheck.collection.library-insert.report-dir=../../chaekcheck-data/library-insert"
 * </pre>
 *
 * then {@code mode=insert} with {@code report-id=<id>}. Exit codes: 0 done, 1 rules error (no report), 2 same-host
 * suspect, 3 order number 2 and up, 4 report missing or different, 5 the database refused the rows (rolled back, a
 * FAILED run row kept). docs/maintenance/1-libraries.md.
 */
@Configuration(proxyBeanMethods = false)
@Profile("library-insert")
@EnableConfigurationProperties(LibraryInsertRunner.Settings.class)
class LibraryInsertRunner {

	static final int REFUSED = 5;

	private static final Logger log = LoggerFactory.getLogger(LibraryInsertRunner.class);

	/**
	 * @param mode {@code preview} (write the report only) or {@code insert} (needs {@code reportId})
	 * @param sqlitePath the collection DB copy, opened read-only
	 * @param rulesDir {@code scripts/library/rules}
	 * @param reportDir folder of the reports, in the data folder (outside git)
	 * @param reportId the preview's report id, for {@code insert}
	 */
	@ConfigurationProperties("chaekcheck.collection.library-insert")
	record Settings(String mode, Path sqlitePath, Path rulesDir, Path reportDir, String reportId) {

		void check() {
			if (!Set.of("preview", "insert").contains(mode)) {
				throw new IllegalStateException(
						"Set chaekcheck.collection.library-insert.mode to preview or insert, not " + mode);
			}
			if (sqlitePath == null || rulesDir == null || reportDir == null) {
				throw new IllegalStateException("Set chaekcheck.collection.library-insert.sqlite-path, rules-dir "
						+ "and report-dir");
			}
		}

	}

	@Bean
	ApplicationRunner libraryInsert(LibraryRegistry registry, JdbcClient jdbc,
			PlatformTransactionManager transactionManager, Settings settings, ConfigurableApplicationContext context) {
		return (ApplicationArguments args) -> {
			settings.check();
			LibraryInsertCommand command = new LibraryInsertCommand(registry, jdbc, transactionManager,
					Clock.systemUTC());
			int exit;
			try {
				exit = (settings.mode().equals("preview")
						? command.preview(settings.sqlitePath(), settings.rulesDir(), settings.reportDir())
						: command.insert(settings.sqlitePath(), settings.rulesDir(), settings.reportDir(),
								settings.reportId()))
					.exitCode();
			}
			catch (LibraryInsertException ex) {
				log.error("library-insert stopped (exit {}): {}", ex.exitCode(), ex.getMessage());
				exit = ex.exitCode();
			}
			catch (org.springframework.dao.DataAccessException ex) {
				exit = REFUSED;
			}
			int code = exit;
			System.exit(SpringApplication.exit(context, () -> code));
		};
	}

}
