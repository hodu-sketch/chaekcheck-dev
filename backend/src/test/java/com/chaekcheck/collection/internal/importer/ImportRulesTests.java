package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.chaekcheck.collection.internal.importer.CollectedCatalog.Libraries;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlatformType;

/**
 * Cycle 19 rules files (ADR-0114, ADR-0115): reading and refusing them, supplier names, and the author and publisher
 * read swapped for the listed libraries only (AC-9), with the swap_before stop.
 */
class ImportRulesTests {

	static final String SWAPPED_HEADER = "collection_id\tname\tdecided_on\tswap_before\tnote\n";

	static final String FAMILIES_HEADER = "supplier\tfamily\tdecided_on\tnote\n";

	static final String DAEGU = "https://library.daegu.test/elib", BUSAN = "https://library.busan.test/elib";

	@TempDir
	Path dir;

	@Test
	void readsBothFiles() throws Exception {
		write(SWAPPED_HEADER + "11\t대구전자도서관\t2026-10-15\t2026-10-17\t\n",
				FAMILIES_HEADER + "Y2Books 전자책\ty2books\t2026-10-16\t\r\n와이투북스\ty2books\t2026-10-16\t\r\n");

		ImportRules rules = ImportRules.read(dir);

		assertThat(rules.swapped(11)).isTrue();
		assertThat(rules.swapped(10)).isFalse();
		assertThat(rules.swapBefore()).containsEntry(11L, LocalDate.of(2026, 10, 17));
		assertThat(rules.family("Y2Books 전자책")).isEqualTo("y2books").isEqualTo(rules.family("와이투북스"));
		assertThat(rules.family("(주)북큐브네트웍스")).isEqualTo("북큐브네트웍스");
		assertThat(rules.family(" ")).isNull();
		assertThat(rules.family(null)).isNull();
	}

	@Test
	void theRepositoryFilesAreValid() {
		ImportRules rules = ImportRules.read(Path.of("../scripts/library/rules"));

		assertThat(rules.swapBefore().keySet()).containsExactlyInAnyOrder(11L, 12L, 15L);
		assertThat(rules.family("Y2Books 전자책")).isEqualTo(rules.family("와이투북스"));
		assertThat(List.of("YES24", "예스이십사", "예스24", "YES24 전자책")).extracting(rules::family).containsOnly("yes24");
		assertThat(List.of("교보문고", "교보 전자책", "교보문고 전자책", "(주)교보문고")).extracting(rules::family)
			.containsOnly("kyobo");
	}

	@Test
	void supplierNamesAreNormalized() {
		assertThat(ImportRules.supplierName("(주) 교보문고")).isEqualTo("교보문고");
		assertThat(ImportRules.supplierName("㈜교보문고")).isEqualTo("교보문고");
		assertThat(ImportRules.supplierName("주식회사 교보문고")).isEqualTo("교보문고");
		assertThat(ImportRules.supplierName("ＹＥＳ２４")).isEqualTo("yes24");
		assertThat(ImportRules.supplierName("()")).isEqualTo("()");
	}

	@Test
	void missingFileOrColumnOrBadValueStops() throws Exception {
		assertThatThrownBy(() -> ImportRules.read(dir)).hasMessageContaining(ImportRules.SWAPPED_FILE)
			.hasMessageContaining("missing");

		write(SWAPPED_HEADER, null);
		assertThatThrownBy(() -> ImportRules.read(dir)).hasMessageContaining(ImportRules.FAMILIES_FILE);

		write("collection_id\tname\tdecided_on\n", FAMILIES_HEADER);
		assertThatThrownBy(() -> ImportRules.read(dir)).hasMessageContaining("missing column swap_before");

		write(SWAPPED_HEADER + "11\t대구\t2026-10-15\t17 Oct\t\n", FAMILIES_HEADER);
		assertThatThrownBy(() -> ImportRules.read(dir)).hasMessageContaining("not a date");

		write(SWAPPED_HEADER, FAMILIES_HEADER + "YES24\tyes24\t2026-10-16\t\nＹＥＳ24\tother\t2026-10-16\t\n");
		assertThatThrownBy(() -> ImportRules.read(dir)).hasMessageContaining("two families");

		assertThatThrownBy(() -> ImportRules.read(null)).hasMessageContaining("rules-dir");
	}

	@Test
	void swappedLibraryRowsAreReadSwappedInBothPasses() throws Exception { // AC-9
		Path sqlite = sqlite("2026-10-12T01:00:00+09:00");
		ImportRules rules = new ImportRules(Map.of(11L, LocalDate.of(2026, 10, 17)), Map.of());
		try (CollectedCatalog collected = CollectedCatalog.open(sqlite)) {
			ImportReport report = new ImportReport();
			Libraries libraries = libraries(collected, rules, report);
			List<Row> first = new ArrayList<>();
			collected.scan(libraries, LocalDate.of(2026, 10, 16), report, first::add);
			List<Row> second = new ArrayList<>();
			collected.scan(libraries, LocalDate.of(2026, 10, 16), null, second::add);

			assertThat(first).extracting(Row::itemKey, Row::authorRaw, Row::publisher)
				.containsExactly(org.assertj.core.groups.Tuple.tuple("D1", "모리세 시게토모", "율리시즈"),
						org.assertj.core.groups.Tuple.tuple("B1", "모리세 시게토모", "율리시즈"));
			assertThat(second).extracting(Row::authorRaw, Row::publisher)
				.isEqualTo(first.stream().map(r -> org.assertj.core.groups.Tuple.tuple(r.authorRaw(), r.publisher()))
					.toList());
			assertThat(report.authorPublisherSwappedRows).isOne();
		}
	}

	@Test
	void aCopyCollectedOnOrAfterSwapBeforeStops() throws Exception { // ADR-0115
		ImportRules rules = new ImportRules(Map.of(11L, LocalDate.of(2026, 10, 17)), Map.of());
		try (CollectedCatalog collected = CollectedCatalog.open(sqlite("2026-10-17T00:00:00+09:00"))) {
			assertThatThrownBy(() -> libraries(collected, rules, new ImportReport()))
				.hasMessageContaining("swap_before 2026-10-17")
				.hasMessageContaining("check_author_publisher_swap.py");
		}
		try (CollectedCatalog collected = CollectedCatalog.open(sqlite("2026-10-16T23:59:59+09:00"))) {
			assertThat(libraries(collected, rules, new ImportReport()).imported().get(11L).authorPublisherSwapped())
				.isTrue();
		}
	}

	private static Libraries libraries(CollectedCatalog collected, ImportRules rules, ImportReport report)
			throws Exception {
		return collected.libraries(Map.of(10L, 10L, 11L, 11L),
				List.of(new LibraryRef(10, BUSAN, PlatformType.ELIB_PORTAL_V1),
						new LibraryRef(11, DAEGU, PlatformType.ELIB_PORTAL_V1)),
				Set.of(), false, rules, report);
	}

	/** Daegu (11) collected swapped at {@code collectedAt}, Busan (10) right, one row each. */
	private Path sqlite(String collectedAt) throws Exception {
		Path sqlite = dir.resolve("swap-" + collectedAt.replace(':', '-') + ".sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite); Statement s = c.createStatement()) {
			s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
			s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
					+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
					+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
					+ " detail_url TEXT)");
			s.execute("INSERT INTO library VALUES (11, '" + DAEGU + "', '" + collectedAt + "'), (10, '" + BUSAN
					+ "', '2026-10-01T00:00:00+09:00')");
			s.execute("INSERT INTO holding (library_id, item_key, title, author_raw, publisher, content_type, detail_url,"
					+ " first_seen_at) VALUES"
					+ " (11, 'D1', '백만장자 마인드', '율리시즈', '모리세 시게토모', 'EBOOK', '" + DAEGU
					+ "/detail?id=D1', '2026-09-29T00:00:00+09:00'),"
					+ " (10, 'B1', '백만장자 마인드', '모리세 시게토모', '율리시즈', 'EBOOK', '" + BUSAN
					+ "/detail?id=B1', '2026-09-29T00:00:00+09:00')");
		}
		return sqlite;
	}

	private void write(String swapped, String families) throws Exception {
		Files.deleteIfExists(dir.resolve(ImportRules.SWAPPED_FILE));
		Files.deleteIfExists(dir.resolve(ImportRules.FAMILIES_FILE));
		if (swapped != null) {
			Files.writeString(dir.resolve(ImportRules.SWAPPED_FILE), swapped, StandardCharsets.UTF_8);
		}
		if (families != null) {
			Files.writeString(dir.resolve(ImportRules.FAMILIES_FILE), families, StandardCharsets.UTF_8);
		}
	}

}
