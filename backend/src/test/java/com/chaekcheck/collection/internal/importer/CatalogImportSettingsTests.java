package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.collection.internal.importer.CatalogImportRunner.Settings;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlatformType;

/**
 * The import settings, without a database. Cycle 11: mode {@code in-place} and grouping {@code compact} by default
 * (user decision 2026-10-12 after the step 11 measurement), each grouping selectable, wrong values refused before
 * anything runs. Cycle 12 (handoff steps 6 and 8-3): the dry-run-only setting {@code exclude-added-later-libraries}
 * (named {@code exclude-candidate-libraries} until cycle 18) is refused outside a dry run, and a dry run of a tiny
 * SQLite with it leaves the added-later library's rows out as {@code library_excluded_added_later}. A dry run writes
 * nothing, so no catalog is needed. Cycle 18: a library whose host has an underscore keeps its e-book rows.
 */
class CatalogImportSettingsTests {

	static final String KYOBO_BASE = "https://kyobo.test:446", BOOKCUBE_BASE = "https://bookcube.test/FxLibrary";

	/** A host with an underscore, like 2624 hs_partners.yes24library.com (cycle 18). */
	static final String UNDERSCORE_BASE = "https://hs_partners.example.test";

	@TempDir
	Path dir;

	private static Settings settings(boolean dryRun, boolean excludeAddedLaterLibraries) {
		return new Settings(Path.of("collection.sqlite"), null, dryRun, null, null, null, null,
				null, excludeAddedLaterLibraries, null);
	}

	private static Settings settings(String mode, Path file, String grouping) {
		return new Settings(Path.of("collected.sqlite"), null, false, null, mode, file, grouping, null, false, null);
	}

	@Test
	void compactAndInPlaceByDefault() {
		Settings defaults = settings(null, null, null);

		assertThat(defaults.mode()).isEqualTo("in-place");
		assertThat(defaults.grouping()).isEqualTo("compact");
		defaults.check();
	}

	@Test
	void rulesDirIsTheRulesFolderByDefault() { // cycle 19
		assertThat(settings(null, null, null).rulesDir()).isEqualTo(Path.of("../scripts/library/rules"));
		Settings set = new Settings(Path.of("c.sqlite"), null, false, null, null, null, null, null, false,
				Path.of("rules"));
		assertThat(set.rulesDir()).isEqualTo(Path.of("rules"));
		assertThat(settings("export", Path.of("f"), null).readsCollection()).isTrue();
		assertThat(settings(null, null, null).readsCollection()).isTrue();
		for (String mode : new String[] { "apply", "finish", "discard" }) {
			assertThat(settings(mode, Path.of("f"), null).readsCollection()).as(mode).isFalse();
		}
	}

	@Test
	void everyGroupingIsSelectable() {
		for (String grouping : new String[] { "objects", "compact", "sql" }) {
			settings(null, null, grouping).check();
			assertThat(CatalogImportRunner.groupers(grouping, null)).isNotNull();
		}
		assertThat(CatalogImportRunner.groupers("compact", null).get()).isInstanceOf(CompactGrouping.class);
		assertThat(CatalogImportRunner.groupers("objects", null).get()).isInstanceOf(BookGrouping.class);
	}

	@Test
	void wrongValuesAreRefused() {
		assertThatThrownBy(() -> settings(null, null, "fast").check()).hasMessageContaining("grouping");
		// the old name of objects (until 2026-10-12)
		assertThatThrownBy(() -> settings(null, null, "current").check()).hasMessageContaining("grouping");
		assertThatThrownBy(() -> settings("copy", null, null).check()).hasMessageContaining("mode");
		assertThatThrownBy(() -> settings("export", null, null).check()).hasMessageContaining("file");
	}

	@Test
	void excludingAddedLaterLibrariesRefusesARealImport() {
		assertThatThrownBy(() -> settings(false, true).check()).isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("dry-run-only");
	}

	@Test
	void excludingAddedLaterLibrariesStartsADryRun() {
		assertThatNoException().isThrownBy(() -> settings(true, true).check());
		assertThatNoException().isThrownBy(() -> settings(false, false).check());
	}

	@Test
	void dryRunLeavesOutTheAddedLaterRows() throws Exception {
		Path sqlite = sqlite();

		ImportReport all = importer().run(sqlite, null, true);
		assertThat(all.otherLibraries).isOne();
		assertThat(all.skipped).doesNotContainKey(CollectedCatalog.EXCLUDED_ADDED_LATER);

		Path out = dir.resolve("dry");
		ImportReport excluded = importer().excludeAddedLaterLibraries(true).dryRunOutput(out).run(sqlite, null, true);
		assertThat(excluded.registeredLibraries).isOne();
		assertThat(excluded.otherLibraries).isZero();
		assertThat(excluded.rowsRead).isEqualTo(3);
		assertThat(excluded.skipped).containsEntry(CollectedCatalog.EXCLUDED_ADDED_LATER, 1L);
		assertThat(excluded.skippedCount()).isEqualTo(all.skippedCount() + 1);
		assertThat(excluded.books).isEqualTo(2);
		assertThat(gunzip(out.resolve("rows.tsv.gz"))).hasSize(2).allMatch(line -> line.startsWith("1\t"));
	}

	@Test
	void underscoreHostLibraryKeepsItsEbookRows() throws Exception { // cycle 18 AC-5 (2624)
		Path sqlite = dir.resolve("underscore.sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
			s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
					+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
					+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
					+ " detail_url TEXT)");
			s.execute("INSERT INTO library VALUES (102467, '" + UNDERSCORE_BASE + "', '2026-09-29T10:00:00+09:00')");
			s.execute("INSERT INTO holding (library_id, item_key, title, author_raw, publisher, content_type, detail_url,"
					+ " first_seen_at) VALUES"
					+ " (102467, 'Y1', '1984', '조지 오웰', '가', 'EBOOK', '" + UNDERSCORE_BASE
					+ "/ebook/detail/?goods_id=1', '2026-09-29T00:00:00+09:00'),"
					+ " (102467, 'Y2', '오디오 1984', '조지 오웰', '가', 'AUDIOBOOK', '" + UNDERSCORE_BASE
					+ "/ebook/detail/?goods_id=2', '2026-09-29T00:00:00+09:00')");
		}
		LibraryCatalog libraryCatalog = mock(LibraryCatalog.class);
		given(libraryCatalog.enabledLibraryRefs())
			.willReturn(List.of(new LibraryRef(2624, UNDERSCORE_BASE, PlatformType.YES24_V2)));
		CatalogImporter importer = new CatalogImporter(libraryCatalog, () -> Map.of(102467L, 2624L), null, null, null,
				Set.of(), Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC));

		ImportReport report = importer.run(sqlite, null, true);

		assertThat(report.otherLibraries).isOne();
		assertThat(report.skipped).doesNotContainKey("detail_url_not_http").containsEntry("kind_audiobook", 1L);
		assertThat(report.books).isOne();
	}

	/** Registered library 1 with two rows, added-later library 100001 (service id 900) with one. */
	private Path sqlite() throws Exception {
		Path sqlite = dir.resolve("test-settings.sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
			s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
					+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
					+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
					+ " detail_url TEXT)");
			s.execute("INSERT INTO library VALUES (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00'),"
					+ " (100001, '" + BOOKCUBE_BASE + "', '2026-09-29T10:00:00+09:00')");
			s.execute("INSERT INTO holding (library_id, item_key, title, author_raw, publisher, content_type, detail_url,"
					+ " first_seen_at) VALUES"
					+ " (1, 'K1', '첫 책', '가', '나', 'EBOOK', '" + KYOBO_BASE + "/detail?id=K1', '2026-09-29T00:00:00+09:00'),"
					+ " (1, 'K2', '둘째 책', '다', '라', 'EBOOK', '" + KYOBO_BASE + "/detail?id=K2', '2026-09-29T00:00:00+09:00'),"
					+ " (100001, 'B1', '셋째 책', '마', '바', 'EBOOK', '" + BOOKCUBE_BASE + "/detail?id=B1',"
					+ " '2026-09-29T00:00:00+09:00')");
		}
		return sqlite;
	}

	/** A dry run reads only the links and the service libraries; the catalog, records and bibliography stay unused. */
	private static CatalogImporter importer() {
		LibraryCatalog libraryCatalog = mock(LibraryCatalog.class);
		given(libraryCatalog.enabledLibraryRefs()).willReturn(List.of(new LibraryRef(1, KYOBO_BASE,
				PlatformType.KYOBO_V1), new LibraryRef(900, BOOKCUBE_BASE, PlatformType.BOOKCUBE_FX)));
		return new CatalogImporter(libraryCatalog, () -> Map.of(1L, 1L, 100001L, 900L), null, null, null, Set.of(),
				Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC));
	}

	private static List<String> gunzip(Path file) throws Exception {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new GZIPInputStream(Files.newInputStream(file)), StandardCharsets.UTF_8))) {
			return reader.lines().toList();
		}
	}

}
