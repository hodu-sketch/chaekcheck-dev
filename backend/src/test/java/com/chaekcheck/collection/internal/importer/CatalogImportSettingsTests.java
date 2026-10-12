package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;

import com.chaekcheck.collection.internal.importer.CatalogImportRunner.Settings;

/**
 * The import settings (cycle 11): mode {@code in-place} and grouping {@code compact} by default (user decision
 * 2026-10-12 after the step 11 measurement), each grouping selectable, wrong values refused before anything runs.
 */
class CatalogImportSettingsTests {

	private static Settings settings(String mode, Path file, String grouping) {
		return new Settings(Path.of("collected.sqlite"), null, false, null, mode, file, grouping, null);
	}

	@Test
	void compactAndInPlaceByDefault() {
		Settings defaults = settings(null, null, null);

		assertThat(defaults.mode()).isEqualTo("in-place");
		assertThat(defaults.grouping()).isEqualTo("compact");
		defaults.check();
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

}
