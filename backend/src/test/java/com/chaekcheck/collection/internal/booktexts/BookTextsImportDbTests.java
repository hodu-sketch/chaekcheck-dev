package com.chaekcheck.collection.internal.booktexts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.chaekcheck.catalog.AiSearchIndex;
import com.chaekcheck.catalog.BookProfiles;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.MostHeldBooks;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.PicksTestData;

/**
 * The {@code book-texts} and {@code book-profiles} modes against db-test (cycle 24 handoff step 4; cycle 26 step 5):
 * the listed books come from picks' own database, the search text and author from {@code catalog.book}, the file's line
 * joins its book, the line of a book not listed is skipped and counted, a second file replaces the first, and every
 * kind lands in {@code catalog}. A file of another export changes nothing (AC-5). Everything runs in the test
 * transaction; the database's latest import is a row made here.
 */
@SpringBootTest
@Transactional
class BookTextsImportDbTests {

	private static final UUID EXPORT = UUID.fromString("0b8f2c64-5a1e-4d6f-9c1b-7d2e3f4a5b6c");

	private static final String FIRST = "# export_id=" + EXPORT + "\nkind\tbook_id\tsource\tmade_on\ttext\n";

	@TempDir
	Path dir;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	ListedBooks listedBooks;

	@Autowired
	BookTextsTransfer transfer;

	@Autowired
	AiSearchIndex searchIndex;

	@Autowired
	BookProfiles profiles;

	@Autowired
	MostHeldBooks mostHeldBooks;

	@Autowired
	PlatformTransactionManager transactionManager;

	@BeforeEach
	void latestImportHasTheExport() {
		jdbc.sql("""
				INSERT INTO collection.catalog_import (source, imported_at, book_count, holding_count, skipped_count,
				  registered_library_count, other_library_count, skipped_by_reason, status, export_id, exported_at)
				VALUES ('test-texts.sqlite', TIMESTAMPTZ '2999-01-01 00:00:00+00', 0, 0, 0, 0, 0, '{}', 'SUCCEEDED', ?,
				  now())""").param(EXPORT).update();
	}

	@Test
	void putsInTheSummaryKindsIntoTheCatalog() throws Exception {
		jdbc.sql("""
				INSERT INTO catalog.tag_dictionary (code, axis, name) VALUES ('m01', 'mood', '잔잔한'),
				  ('s01', 'subject', '소설')""").update();
		jdbc.sql("INSERT INTO catalog.book_tag (book_id, tag_code, made_on) VALUES (930101, 's01', DATE '2026-10-01')")
			.update();
		int searchBooks = jdbc.sql("SELECT count(*) FROM catalog.book_search").query(Integer.class).single();

		BookTextsImport.Report report = command().run(file(FIRST + """
				for_you_blurb\t930101\t\t2026-10-24\t별과 우주를 차근차근 풀어 주는 책
				tags\t930101\t\t2026-10-24\tm01,zz9
				cover_file\t930101\t\t\t0123456789abcdef0123456789abcdef01234567
				"""), false);

		assertThat(report.result()).isNull();
		assertThat(report.tagCodesSkipped()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT summary FROM catalog.book_summary WHERE book_id = 930101").query(String.class).single())
			.isEqualTo("별과 우주를 차근차근 풀어 주는 책");
		assertThat(jdbc.sql("SELECT tag_code FROM catalog.book_tag WHERE book_id = 930101").query(String.class).list())
			.containsExactly("m01");
		assertThat(jdbc.sql("SELECT file_key FROM catalog.book_cover WHERE book_id = 930101").query(String.class).single())
			.isEqualTo("0123456789abcdef0123456789abcdef01234567");
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book_search").query(Integer.class).single())
			.isEqualTo(searchBooks);
	}

	@Test
	void aFileOfAnotherExportChangesNothing() throws Exception { // AC-5
		Path file = file("# export_id=" + UUID.randomUUID() + "\nkind\tbook_id\tsource\tmade_on\ttext\n"
				+ "for_you_blurb\t930101\t\t2026-10-24\t다른 기준의 요약\n");

		assertThatThrownBy(() -> command().run(file, false)).isInstanceOf(BookTextsFile.RefusedException.class);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book_summary WHERE book_id = 930101")
			.query(Integer.class)
			.single()).isZero();
	}

	@Test
	void putsInEveryListedBookAndTheFileLines() throws Exception {
		PicksTestData data = new PicksTestData(jdbc);
		data.run();
		data.ourBook(930_001, null, "토지. 1(1부 1권)", "박경리", 3);
		data.ourBook(930_002, null, "코스모스", "칼 세이건", 1);
		jdbc.sql("UPDATE catalog.book SET author_display = '박경리 지음' WHERE id = 930001").update();
		jdbc.sql("UPDATE catalog.book SET author_display = '칼 세이건' WHERE id = 930002").update();
		jdbc.sql("""
				INSERT INTO picks.list_field (list_key, code, name, position) VALUES ('leedongjin', 'novel', '소설', 99)
				ON CONFLICT (list_key, code) DO NOTHING""").update();
		leedongjin(1, "토지", 930_001L);
		leedongjin(2, "코스모스", 930_002L);
		BookTextsImport command = command();

		BookTextsImport.Report first = command.run(file(FIRST + """
				search_line\t930001\tintro\t2026-10-24\t한 집안의 오랜 역사를 그린 대하소설
				search_line\t939999\tintro\t2026-10-24\t목록에 없는 책
				"""), false);

		assertThat(first.result().linesNotListed()).isEqualTo(1);
		// the catalog's title now, not the list's shown name (cycle 26, ADR-0165)
		assertThat(rows()).containsExactly(tuple(930_001L, "토지. 1(1부 1권)", "박경리 지음", "한 집안의 오랜 역사를 그린 대하소설"),
				tuple(930_002L, "코스모스", "칼 세이건", null));

		command.run(file(FIRST + "search_line\t930002\tbibliographic\t2026-10-25\t우주를 다룬 교양서\n"), false);

		assertThat(rows()).containsExactly(tuple(930_001L, "토지. 1(1부 1권)", "박경리 지음", null),
				tuple(930_002L, "코스모스", "칼 세이건", "우주를 다룬 교양서"));
	}

	@Test
	void profilesReplaceTheCatalogsAndAnotherExportIsRefused() throws Exception { // AC-5
		BookProfilesImport command = new BookProfilesImport(transfer, () -> ExportIds.latest(jdbc));
		Path other = file("# export_id=" + UUID.randomUUID() + "\n930001\t문학\t\t1\t성장|가족\t한국\t성인\t보통\t장편\n");

		assertThatThrownBy(() -> command.run(other, false)).isInstanceOf(BookTextsFile.RefusedException.class);
		assertThat(profiles.version()).isEqualTo(
				String.valueOf(jdbc.sql("SELECT count(*) FROM catalog.book_profile").query(Long.class).single()));

		BookProfilesImport.Report report = command.run(file("# export_id=" + EXPORT + "\n"
				+ "930001\t문학\t\t1\t성장|가족\t한국\t성인\t보통\t장편\n930002\t\t\t\t\t\t\t\t\n"), false);

		assertThat(report.profiles()).isEqualTo(2);
		assertThat(profiles.all()).containsOnlyKeys(930001L, 930002L);
		assertThat(profiles.all().get(930001L))
			.isEqualTo(new BookProfiles.Profile("문학", null, true, List.of("성장", "가족"), "한국", "성인", "보통",
					List.of("장편")));
		assertThat(profiles.all().get(930002L).fiction()).isNull();
		assertThat(command.run(other, true).forced()).isTrue();
	}

	private BookTextsImport command() {
		return new BookTextsImport(listedBooks, mostHeldBooks, 0, transfer, searchIndex, () -> ExportIds.latest(jdbc),
				new TransactionTemplate(transactionManager));
	}

	private List<Tuple> rows() {
		return jdbc.sql("""
				SELECT s.book_id, b.title, b.author_display, s.line FROM catalog.book_search s
				JOIN catalog.book b ON b.id = s.book_id
				WHERE s.book_id BETWEEN 930001 AND 930002 ORDER BY s.book_id""")
			.query((rs, n) -> tuple(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))
			.list();
	}

	private void leedongjin(int position, String title, Long bookId) {
		jdbc.sql("""
				INSERT INTO picks.list_book (list_key, field_code, position, title, recommended_month, video_id, book_id)
				VALUES ('leedongjin', 'novel', :position, :title, DATE '2026-10-01', 'vid' || :position, :bookId)""")
			.param("position", position)
			.param("title", title)
			.param("bookId", bookId)
			.update();
	}

	private Path file(String text) throws Exception {
		Path file = Files.createTempFile(dir, "book-texts", ".tsv");
		Files.writeString(file, text, StandardCharsets.UTF_8);
		return file;
	}

}
