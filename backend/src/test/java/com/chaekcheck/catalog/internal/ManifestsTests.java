package com.chaekcheck.catalog.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Cycle 13 (ADR-0086), cycle 21 (ADR-0123): the manifest of format 3 and its notes, read from the line {@code pg_restore} prints for the
 * table comment (no database needed).
 */
class ManifestsTests {

	@Test
	void aFormatOneFileIsRefused() {
		assertThatThrownBy(() -> Manifests.parse(restoreOutput(1, "{}")))
			.hasMessageContaining("manifest format 1")
			.hasMessageContaining("reads 3");
	}

	@Test
	void aFormatTwoFileIsRefused() {
		// a file exported before cycle 21 has no recommended lists: finish or discard it with the code that made it
		assertThatThrownBy(() -> Manifests.parse(restoreOutput(2, "{\"libraryPart\":[]}")))
			.hasMessageContaining("manifest format 2")
			.hasMessageContaining("reads 3");
	}

	@Test
	void longNotesComeBackWhole() {
		// tens of KB, as a library part with many runs; Korean, quotes and an apostrophe (doubled in SQL)
		StringBuilder runs = new StringBuilder();
		for (int i = 0; i < 400; i++) {
			runs.append(i == 0 ? "" : ",")
				.append("{\"reportId\":\"0123456789ab\",\"name\":\"한국선원복지고용센터 O'Neil \\\"전자\\\" ")
				.append(i)
				.append("\",\"baseUrl\":\"http://ebook.koswec.or.kr\"}");
		}
		String notes = "{\"libraryPart\":[" + runs + "]}";
		assertThat(notes.length()).isGreaterThan(40_000);

		assertThat(Manifests.parse(restoreOutput(JdbcCatalogLoad.FORMAT_VERSION, notes)).notes())
			.isEqualTo(notes);
	}

	/** What {@code pg_restore -s -t load_manifest -f -} prints, around the comment line. */
	private static String restoreOutput(int version, String notes) {
		String row = "{\"format_version\":" + version + ",\"export_id\":\"6f1c0c3e-8f7a-4f5e-9a51-3c2d1b0a9e88\","
				+ "\"exported_at\":\"2026-10-12T20:00:00+09:00\",\"book_count\":2,\"book_max_id\":2,"
				+ "\"book_sequence\":2,\"holding_count\":2,\"holding_max_id\":2,\"holding_sequence\":2,"
				+ "\"columns_hash\":\"abc\",\"notes\":" + notes + "}";
		return "--\n-- PostgreSQL database dump\n--\nSET statement_timeout = 0;\n"
				+ "COMMENT ON TABLE catalog_next.load_manifest IS '" + row.replace("'", "''") + "';\n";
	}

}
