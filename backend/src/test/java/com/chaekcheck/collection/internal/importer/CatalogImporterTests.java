package com.chaekcheck.collection.internal.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlatformType;

/**
 * One import from a tiny SQLite and TTL written by the test into db-test: grouping ①②③④, only-one-record rule,
 * filters, future dates, COPY result (cycle 04), and since cycle 07 the links, the base URL check, the new
 * exclusions, the date rules, {@code library_ids} and the import record (plan handoff steps 3~5). The first test
 * keeps the cycle 04 sample and numbers: the two-pass import gives what the one-pass import gave. The catalog tables
 * and the test's import records are emptied afterwards.
 */
@SpringBootTest
class CatalogImporterTests {

	static final String KYOBO_BASE = "https://kyobo.test:446", YES24_BASE = "https://yes24.test",
			PORTAL_BASE = "https://portal.test/elib";

	@Autowired
	CatalogLoad catalogLoad;

	@Autowired
	JdbcClient jdbc;

	@Autowired
	DataSource dataSource;

	NationalBibliographyStore bibliography;

	@TempDir
	Path dir;

	LibraryCatalog libraryCatalog = mock(LibraryCatalog.class);

	/** Registered libraries link to themselves; 100001 was added later, with service id 900; 100002 has no link. */
	Map<Long, Long> links = Map.of(1L, 1L, 2L, 2L, 3L, 3L, 4L, 4L, 5L, 5L, 100001L, 900L);

	@BeforeEach
	void setUp() {
		bibliography = new NationalBibliographyStore(dataSource);
		given(libraryCatalog.enabledLibraryRefs()).willReturn(List.of(new LibraryRef(1, KYOBO_BASE, PlatformType.KYOBO_V1),
				new LibraryRef(2, YES24_BASE, PlatformType.YES24_V2), new LibraryRef(3, "https://other.test",
						PlatformType.KYOBO_V1),
				new LibraryRef(4, PORTAL_BASE, PlatformType.ELIB_PORTAL_V1),
				new LibraryRef(5, "https://portal2.test/elib", PlatformType.ELIB_PORTAL_V1),
				new LibraryRef(900, "https://bookcube.test/FxLibrary", PlatformType.BOOKCUBE_FX)));
	}

	@AfterEach
	void empty() {
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot")
			.update();
		jdbc.sql("DELETE FROM collection.catalog_import WHERE source LIKE 'test-%'").update();
		jdbc.sql("TRUNCATE collection.national_bibliography_record, collection.national_bibliography_source").update();
	}

	private CatalogImporter importer(Set<Long> placeholderDateLibraries) {
		Clock clock = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);
		return new CatalogImporter(libraryCatalog, () -> links, catalogLoad, new ImportRecords(jdbc), bibliography,
				placeholderDateLibraries, clock);
	}

	@Test
	void importsGroupsAndSkips() throws Exception {
		Path sqlite = dir.resolve("test-catalog.sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite)) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE library (id INTEGER, name TEXT, platform_type TEXT, base_url TEXT, host TEXT,"
						+ " seed_holdings_count INTEGER, last_full_success_at TEXT, last_full_run_id INTEGER, source TEXT)");
				s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, platform TEXT, title TEXT,"
						+ " author_raw TEXT, publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT,"
						+ " content_kind TEXT, content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT,"
						+ " alt_code TEXT, detail_url TEXT)");
				s.execute("INSERT INTO library (id, base_url, last_full_success_at) VALUES"
						+ " (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00'),"
						+ " (2, '" + YES24_BASE + "', '2026-09-30T01:00:00+09:00'),"
						+ " (3, 'https://different.test', '2026-09-30T01:00:00+09:00'),"
						+ " (4, '" + PORTAL_BASE + "', NULL),"
						+ " (5, 'https://portal2.test/elib', '2026-09-30T01:00:00+09:00'),"
						+ " (100002, 'https://bookcube.test', '2026-09-30T01:00:00+09:00')");
			}
			String insert = "INSERT INTO holding (library_id, item_key, title, author_raw, publisher, published_on,"
					+ " content_type, removed_at, detail_url, first_seen_at, last_seen_at, alt_code)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-29T00:00:00+09:00', '2026-09-29T08:00:00+09:00', ?)";
			try (PreparedStatement p = c.prepareStatement(insert)) {
				// ① same Kyobo code in two libraries (Kyobo and YES24 share a numbering) and ② its paper ISBN
				row(p, 1, "4808954682152", "작별하지 않는다", "한강 저", "문학동네", "2021-09-09", "EBOOK", null, KYOBO_BASE);
				row(p, 2, "4808954682152", "작별하지 않는다", "한강 저", "문학동네", "2021-09-09", "EBOOK", null, YES24_BASE);
				// ③ no code ISBN; the bibliography record gives the same ISBN, so it joins the book above
				row(p, 2, "99887766", "작별하지 않는다 : 특별판", "한강 지음", "(주)문학동네", "2045-12-08", "EBOOK", null,
						YES24_BASE);
				// ③ two bibliography records match: no ISBN
				row(p, 1, "AAA1", "모호한 책", "가나다", "라마바", "2020-01-01", "EBOOK", null, KYOBO_BASE);
				// portal codes are per site: same book_idx in two portals stays two books
				row(p, 4, "EBK:77", "포털 책 하나", "가", "나", "2019-01-01", "EBOOK", null, PORTAL_BASE);
				row(p, 5, "EBK:77", "포털 책 둘", "다", "라", "2019-01-01", "KIDS_BOOK", null, "https://portal2.test/elib");
				// ④ same whole title, author and publisher in another portal: one book; "(개정판)" and author-less stay apart
				row(p, 5, "EBK:90", "포털 책 하나", "가", "나", "2019-01-01", "EBOOK", null, "https://portal2.test/elib");
				row(p, 5, "EBK:91", "포털 책 하나(개정판)", "가", "나", "2024-01-01", "EBOOK", null, "https://portal2.test/elib");
				row(p, 4, "EBK:92", "저자 없는 책", null, "나", "2019-01-01", "EBOOK", null, PORTAL_BASE);
				row(p, 5, "EBK:93", "저자 없는 책", null, "나", "2019-01-01", "EBOOK", null, "https://portal2.test/elib");
				// only a future date: no date
				row(p, 4, "EBK:78", "미래 책", "가", "나", "2045-07-00", "EBOOK", null, PORTAL_BASE);
				// skipped rows
				row(p, 1, "S1", "오디오", "가", "나", null, "AUDIOBOOK", null, KYOBO_BASE);
				row(p, 1, "S2", "동영상", "가", "나", null, "VIDEO_BOOK", null, KYOBO_BASE);
				row(p, 1, "S3", "빠진 책", "가", "나", null, "EBOOK", "2026-09-29T00:00:00+09:00", KYOBO_BASE);
				row(p, 1, "S4", "다른 호스트", "가", "나", null, "EBOOK", null, "https://elsewhere.test");
				row(p, 1, "S5", "주소 없음", "가", "나", null, "EBOOK", null, null);
				row(p, 3, "S6", "주소가 다른 도서관", "가", "나", null, "EBOOK", null, "https://different.test");
				row(p, 100002, "S7", "북큐브", "가", "나", null, "EBOOK", null, "https://bookcube.test");
				p.executeBatch();
			}
		}
		Path ttl = dir.resolve("ttl");
		Files.createDirectories(ttl);
		Files.writeString(ttl.resolve("book_0.ttl"), """
				@prefix nlk:   <http://lod.nl.go.kr/resource/> .
				@prefix dc:    <http://purl.org/dc/elements/1.1/> .
				@prefix dcterms: <http://purl.org/dc/terms/> .
				@prefix bibo:  <http://purl.org/ontology/bibo/> .

				nlk:KMO1  a                     bibo:Book ;
				        bibo:isbn                       "978-89-546-8215-2 (03810)" ;
				        dc:creator                      "한강 지음" ;
				        dc:publisher                    "문학동네#" ;
				        dcterms:title                   "(장편소설) 작별하지 않는다" .

				nlk:KMO2  a                     bibo:Book ;
				        bibo:isbn                       "9788900000001" ;
				        dc:creator                      "가나다" ;
				        dc:publisher                    "라마바" ;
				        dcterms:title                   "모호한 책" .

				nlk:KMO3  a                     bibo:Book ;
				        bibo:isbn                       "9788900000018" ;
				        dc:creator                      "가나다 저" ;
				        dc:publisher                    "라마바" ;
				        dcterms:title                   "모호한 책" .

				nlk:KMO4  a                     bibo:Book ;
				        dc:creator                      "무 ISBN" ;
				        dcterms:title                   "ISBN 없는 기록" .
				""", StandardCharsets.UTF_8);
		ImportReport report = importer(Set.of()).run(sqlite, ttl, false);

		// cycle 04 counted library 3 (other base URL) as not in service; it is linked now, so the check names it
		assertThat(report.skipped).containsExactlyInAnyOrderEntriesOf(Map.of("kind_audiobook", 1L, "kind_video_book",
				1L, "removed", 1L, "detail_host_mismatch", 1L, "no_detail_url", 1L, "library_not_in_service", 1L,
				"library_base_url_mismatch", 1L));
		assertThat(report.booksAfterPlatformCode).isEqualTo(10);
		assertThat(report.booksAfterNationalBibliography).isEqualTo(9);
		assertThat(report.booksAfterSameText).isEqualTo(8);
		assertThat(report.nationalBibliographyIsbnAttached).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(8);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.holding").query(Long.class).single()).isEqualTo(11);
		assertThat(jdbc.sql("""
				SELECT b.title, count(h.id) FROM catalog.book b JOIN catalog.holding h ON h.book_id = b.id
				WHERE b.title LIKE '포털 책 하나%' OR b.title = '저자 없는 책' GROUP BY b.id ORDER BY b.title, count(h.id)""")
			.query((rs, n) -> rs.getString(1) + " " + rs.getInt(2))
			.list()).containsExactly("저자 없는 책 1", "저자 없는 책 1", "포털 책 하나 2", "포털 책 하나(개정판) 1");

		Map<String, Object> farewell = jdbc.sql("""
				SELECT b.title, b.isbn13, b.published_on::text AS published_on, b.publisher_norm, count(h.id) AS holdings
				FROM catalog.book b JOIN catalog.holding h ON h.book_id = b.id
				WHERE b.isbn13 = '9788954682152' GROUP BY b.id""").query().singleRow();
		assertThat(farewell).containsEntry("title", "작별하지 않는다")
			.containsEntry("published_on", "2021-09-09")
			.containsEntry("publisher_norm", "문학동네")
			.containsEntry("holdings", 3L);
		assertThat(jdbc.sql("SELECT isbn13 FROM catalog.book WHERE title = '모호한 책'").query().singleRow().get("isbn13")).isNull();
		assertThat(jdbc.sql("SELECT published_on FROM catalog.book WHERE title = '미래 책'").query().singleRow().get("published_on"))
			.isNull();
		assertThat(jdbc.sql("SELECT content_type FROM catalog.book WHERE title = '포털 책 둘'").query(String.class).single())
			.isEqualTo("KIDS_BOOK");
		assertThat(jdbc.sql("SELECT role FROM catalog.book_contributor c JOIN catalog.book b ON b.id = c.book_id"
				+ " WHERE b.isbn13 = '9788954682152'").query(String.class).list()).containsExactly("AUTHOR");
		// library 4 has no full success: its newest last_seen_at is used
		assertThat(jdbc.sql("SELECT library_id FROM catalog.catalog_library_snapshot ORDER BY library_id")
			.query(Long.class)
			.list()).containsExactly(1L, 2L, 4L, 5L);
		assertThat(report.librariesWithoutFullSuccess).isEqualTo(1);
		assertThat(jdbc.sql("""
				SELECT book_count, holding_count, skipped_count, registered_library_count, other_library_count,
				  skipped_by_reason ->> 'library_base_url_mismatch' AS mismatch
				FROM collection.catalog_import WHERE source = 'test-catalog.sqlite'""")
			.query()
			.singleRow()).containsEntry("book_count", 8)
			.containsEntry("holding_count", 11)
			.containsEntry("skipped_count", 7)
			.containsEntry("registered_library_count", 4)
			.containsEntry("other_library_count", 0)
			.containsEntry("mismatch", "1");
		// cycle 08 AC-7: the collected period is the oldest and newest library snapshot; every step has a time
		assertThat(jdbc.sql("""
				SELECT i.collected_from = s.oldest AND i.collected_to = s.newest
				FROM collection.catalog_import i,
				  (SELECT min(collected_at) AS oldest, max(collected_at) AS newest FROM catalog.catalog_library_snapshot) s
				WHERE i.source = 'test-catalog.sqlite'""").query(Boolean.class).single()).isTrue();
		assertThat(jdbc.sql("""
				SELECT jsonb_object_keys(step_seconds) FROM collection.catalog_import
				WHERE source = 'test-catalog.sqlite'""").query(String.class).list()).containsExactlyInAnyOrder("read",
						"group", "nb", "normalize", "same_text", "books_copy", "holdings_read", "holdings_copy", "match", "write",
						"index", "analyze", "swap", "load");
		// ADR-0055: the book's libraries, sorted
		assertThat(jdbc.sql("SELECT library_ids::text FROM catalog.book WHERE isbn13 = '9788954682152'")
			.query(String.class)
			.single()).isEqualTo("{1,2}");

		// a second import replaces the data; the records are kept as the history
		importer(Set.of()).run(sqlite, null, false);
		assertThat(jdbc.sql("SELECT count(*) FROM collection.catalog_import WHERE source = 'test-catalog.sqlite'")
			.query(Long.class)
			.single()).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(9);
	}

	/**
	 * Cycle 07: a library added later comes in under its service id through the link; the sound and video kinds are left out;
	 * 8-digit dates are read; the placeholder-date library's dates before 1950 are dropped (only there); the most
	 * common values of a book are counted over its rows although the first pass keeps counts, not rows.
	 */
	/** Cycle 08 handoff step 4-7: a load that is not swapped in is kept in the history as FAILED with its reason. */
	@Test
	void failedLoadIsRecorded() throws Exception {
		Path sqlite = dir.resolve("test-failed.sqlite");
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite)) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
				s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
						+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
						+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
						+ " detail_url TEXT)");
				s.execute("INSERT INTO library VALUES (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00')");
				s.execute("INSERT INTO holding (library_id, item_key, title, content_type, detail_url, first_seen_at)"
						+ " VALUES (1, 'K1', '책', 'EBOOK', '" + KYOBO_BASE + "/detail?id=K1', '2026-09-29T00:00:00+09:00')");
			}
		}
		jdbc.sql("CREATE TABLE public.import_test_outside (book_id bigint REFERENCES catalog.book (id))").update();
		try {
			org.assertj.core.api.Assertions.assertThatThrownBy(() -> importer(Set.of()).run(sqlite, null, false))
				.isInstanceOf(CatalogLoad.NotSwappedException.class);
		}
		finally {
			jdbc.sql("DROP TABLE public.import_test_outside").update();
		}
		assertThat(jdbc.sql("""
				SELECT status || ' ' || failure_reason FROM collection.catalog_import WHERE source = 'test-failed.sqlite'""")
			.query(String.class)
			.single()).isEqualTo("FAILED outside_foreign_key");
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isZero();
	}

	@Test
	void importsAddedLibrariesThroughLinksWithTheNewRules() throws Exception {
		Path sqlite = dir.resolve("test-cycle07.sqlite");
		String kyobo = KYOBO_BASE + "/detail?id=";
		String yes24 = YES24_BASE + "/detail?id=";
		String bookcube = "https://bookcube.test/FxLibrary/product/view/?num=";
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite)) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
				s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
						+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
						+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
						+ " detail_url TEXT)");
				s.execute("INSERT INTO library VALUES (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00'),"
						+ " (2, '" + YES24_BASE + "', '2026-09-30T01:00:00+09:00'),"
						+ " (100001, 'https://bookcube.test/FxLibrary', '2026-09-30T01:00:00+09:00'),"
						+ " (100002, 'https://nolink.test', '2026-09-30T01:00:00+09:00')");
			}
			String insert = "INSERT INTO holding (library_id, item_key, title, published_on, published_raw,"
					+ " content_kind, content_type, detail_url, author_raw, publisher, first_seen_at)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, '2026-09-29T00:00:00+09:00')";
			try (PreparedStatement p = c.prepareStatement(insert)) {
				// one shared code in libraries 1 and 2, and the added library's book with the same text (④):
				// the title most rows have wins
				kind(p, 1, "K1", "다수 제목", null, "2020-01-01", "전자책", "EBOOK", kyobo + "K1", "가");
				kind(p, 2, "K1", "소수 제목", null, "20200101", "전자책", "EBOOK", yes24 + "K1", "가");
				kind(p, 100001, "160202793", "다수 제목", null, null, "book/epub", "EBOOK", bookcube + "160202793", "가");
				// 8-digit dates: a real day and one that does not exist
				kind(p, 1, "D1", "여덟 자리 날짜", null, "20240315", "전자책", "EBOOK", kyobo + "D1", "가");
				kind(p, 1, "D2", "없는 날짜", null, "20241399", "전자책", "EBOOK", kyobo + "D2", "가");
				// a placeholder date stays in a normal library and is dropped in the placeholder-date one
				kind(p, 1, "P1", "옛 책", "1900-01-01", null, "전자책", "EBOOK", kyobo + "P1", "가");
				kind(p, 100001, "160202794", "자리 날짜", "1900-01-01", null, "book/pdf", "EBOOK", bookcube + "160202794", "다");
				// sound and video kinds (ADR-0054 Q2)
				kind(p, 100001, "W1", "소리", null, null, "book/wma", "UNKNOWN", bookcube + "W1", "가");
				kind(p, 100001, "W2", "영상", null, null, "book/vod", "UNKNOWN", bookcube + "W2", "가");
				kind(p, 100001, "W3", "영상 둘", null, null, "book/wmv", "UNKNOWN", bookcube + "W3", "가");
				kind(p, 1, "V1", "교보 동영상", null, null, "동영상", "UNKNOWN", kyobo + "V1", "가");
				// no link: not in service
				kind(p, 100002, "N1", "연결 없음", null, null, "전자책", "EBOOK", "https://nolink.test/N1", "가");
				p.executeBatch();
			}
		}

		ImportReport report = importer(Set.of(100001L)).run(sqlite, null, false);

		assertThat(report.skipped).containsExactlyInAnyOrderEntriesOf(Map.of("kind_sound_video", 4L,
				"library_not_in_service", 1L));
		assertThat(report.registeredLibraries).isEqualTo(2);
		assertThat(report.otherLibraries).isEqualTo(1);
		assertThat(report.placeholderDatesCleared).isEqualTo(1);
		Map<String, Object> shared = jdbc.sql("""
				SELECT b.published_on::text AS published_on, b.library_ids::text AS library_ids
				FROM catalog.book b WHERE b.title = '다수 제목'""").query().singleRow();
		assertThat(shared).containsEntry("published_on", "2020-01-01").containsEntry("library_ids", "{1,2,900}");
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book WHERE title = '소수 제목'").query(Long.class).single())
			.isZero();
		assertThat(dateOf("여덟 자리 날짜")).isEqualTo("2024-03-15");
		assertThat(dateOf("없는 날짜")).isNull();
		assertThat(dateOf("옛 책")).isEqualTo("1900-01-01");
		assertThat(dateOf("자리 날짜")).isNull();
		assertThat(jdbc.sql("SELECT DISTINCT library_id FROM catalog.holding ORDER BY library_id")
			.query(Long.class)
			.list()).containsExactly(1L, 2L, 900L);
		assertThat(jdbc.sql("SELECT library_id FROM catalog.catalog_library_snapshot ORDER BY library_id")
			.query(Long.class)
			.list()).containsExactly(1L, 2L, 900L);
		assertThat(jdbc.sql("SELECT holding_count, other_library_count FROM collection.catalog_import"
				+ " WHERE source = 'test-cycle07.sqlite'")
			.query()
			.singleRow()).containsEntry("holding_count", 7).containsEntry("other_library_count", 1);
	}

	/**
	 * Cycle 19 (ADR-0113~0115) from one tiny SQLite: marks and stuck role words in author texts, the leading note,
	 * supplier codes and the author and publisher of a swapped portal (AC-1, 2, 4, 5, 6, 9, 17).
	 */
	@Test
	void cycle19AuthorTitleAndCodeRules() throws Exception {
		Path sqlite = dir.resolve("test-cycle19.sqlite");
		String portal2 = "https://portal2.test/elib";
		String bookcube = "https://bookcube.test/FxLibrary";
		try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + sqlite)) {
			try (Statement s = c.createStatement()) {
				s.execute("CREATE TABLE library (id INTEGER, base_url TEXT, last_full_success_at TEXT)");
				s.execute("CREATE TABLE holding (library_id INTEGER, item_key TEXT, title TEXT, author_raw TEXT,"
						+ " publisher TEXT, published_on TEXT, published_raw TEXT, supplier TEXT, content_kind TEXT,"
						+ " content_type TEXT, first_seen_at TEXT, last_seen_at TEXT, removed_at TEXT, alt_code TEXT,"
						+ " detail_url TEXT)");
				s.execute("INSERT INTO library VALUES (1, '" + KYOBO_BASE + "', '2026-09-29T10:00:00+09:00'),"
						+ " (2, '" + YES24_BASE + "', '2026-09-29T10:00:00+09:00'),"
						+ " (4, '" + PORTAL_BASE + "', '2026-09-29T10:00:00+09:00'),"
						+ " (5, '" + portal2 + "', '2026-09-29T10:00:00+09:00'),"
						+ " (100001, '" + bookcube + "', '2026-09-29T10:00:00+09:00')");
			}
			String insert = "INSERT INTO holding (library_id, item_key, title, author_raw, publisher, supplier, alt_code,"
					+ " detail_url, content_kind, content_type, first_seen_at)"
					+ " VALUES (?, ?, ?, ?, ?, ?, ?, ?, '전자책', 'EBOOK', '2026-09-29T00:00:00+09:00')";
			try (PreparedStatement p = c.prepareStatement(insert)) {
				// AC-1: angle brackets and plain names → one book
				coded(p, 1, "K1", "채널 전쟁", "<김정은>,<김지혜> 저", "가나", null, null, KYOBO_BASE);
				coded(p, 5, "E1", "채널 전쟁", "김정은, 김지혜 저", "가나", null, null, portal2);
				// AC-2: plain, angle brackets, 김근아 (same supplier code), leading note, swapped portal → one book;
				// the modifier without brackets stays apart
				coded(p, 5, "E2", "백만장자 마인드", "모리세 시게토모", "율리시즈", null, null, portal2);
				coded(p, 1, "K2", "백만장자 마인드", "<모리세 시게토모> 저", "율리시즈", null, null, KYOBO_BASE);
				coded(p, 100001, "B2", "백만장자 마인드", "김근아 저자", "율리시즈", "Y2Books 전자책", "1210492004114",
						bookcube);
				coded(p, 1, "1210492004114", "(부를 끌어 당기는) 백만장자 마인드", "모리세 시게토모", "율리시즈", "와이투북스",
						null, KYOBO_BASE);
				coded(p, 2, "1210492004114", "(부를 끌어 당기는) 백만장자 마인드", "모리세 시게토모", "율리시즈", "와이투북스",
						null, YES24_BASE);
				coded(p, 4, "E3", "백만장자 마인드", "율리시즈", "모리세 시게토모", null, null, PORTAL_BASE);
				coded(p, 5, "E4", "부를 끌어 당기는 백만장자 마인드", "모리세 시게토모", "율리시즈", null, null, portal2);
				// AC-5: 〈 〉 marks → one book
				coded(p, 5, "E5", "슈퍼차이나", "KBS 〈슈퍼차이나〉 제작팀", "가나", null, null, portal2);
				coded(p, 1, "K5", "슈퍼차이나", "KBS 슈퍼차이나 제작팀", "가나", null, null, KYOBO_BASE);
				// AC-6: (만화) is kept → two books
				coded(p, 5, "E6", "(만화) 삼국지", "이문열", "가나", null, null, portal2);
				coded(p, 1, "K6", "삼국지", "이문열", "가나", null, null, KYOBO_BASE);
				// AC-17: a stuck role word is spaced in the author field; AC-4: [ ] stays
				coded(p, 5, "E7", "앨리스", "루이스 캐럴 저이소연 역", "펭귄", null, null, portal2);
				coded(p, 5, "E8", "풍경", "존 버거 [지음]", "열화당", null, null, portal2);
				p.executeBatch();
			}
		}
		ImportRules rules = new ImportRules(Map.of(4L, java.time.LocalDate.of(2026, 10, 17)),
				Map.of(ImportRules.supplierName("Y2Books 전자책"), "y2books", ImportRules.supplierName("와이투북스"),
						"y2books"));

		ImportReport report = importer(Set.of()).rules(rules).run(sqlite, null, false);

		assertThat(report.authorPublisherSwappedRows).isOne();
		assertThat(report.supplierCodeJoins).isOne();
		assertThat(report.codeJoinedIsbnConflicts).isZero();
		assertThat(report.leadingNoteCut).isPositive();
		assertThat(booksTitled("채널 전쟁")).isOne();
		assertThat(booksTitled("%백만장자 마인드")).isEqualTo(2);
		assertThat(jdbc.sql("SELECT count(DISTINCT book_id) FROM catalog.holding WHERE item_key IN"
				+ " ('E2', 'K2', 'B2', '1210492004114', 'E3')").query(Long.class).single()).isOne();
		assertThat(booksTitled("슈퍼차이나")).isOne();
		assertThat(booksTitled("%삼국지")).isEqualTo(2);
		// AC-9: the swapped portal's holding keeps the right values
		assertThat(jdbc.sql("SELECT author_raw || ' / ' || publisher_raw FROM catalog.holding WHERE item_key = 'E3'")
			.query(String.class).single()).isEqualTo("모리세 시게토모 / 율리시즈");
		// AC-4, AC-17
		List<String> authors = jdbc.sql("SELECT author_display FROM catalog.book").query(String.class).list();
		assertThat(authors).noneMatch(a -> a != null && a.matches(".*[<>〈〉「」《》].*"));
		assertThat(authors).contains("루이스 캐럴 저 이소연 역", "존 버거 [지음]");
		assertThat(jdbc.sql("SELECT name || ':' || role FROM catalog.book_contributor c JOIN catalog.book b"
				+ " ON b.id = c.book_id WHERE b.title = '앨리스' ORDER BY position").query(String.class).list())
			.containsExactly("루이스 캐럴:AUTHOR", "이소연:TRANSLATOR");
	}

	private long booksTitled(String pattern) {
		return jdbc.sql("SELECT count(*) FROM catalog.book WHERE title LIKE :t").param("t", pattern)
			.query(Long.class).single();
	}

	private static void coded(PreparedStatement p, long library, String key, String title, String author,
			String publisher, String supplier, String altCode, String base) throws Exception {
		p.setLong(1, library);
		p.setString(2, key);
		p.setString(3, title);
		p.setString(4, author);
		p.setString(5, publisher);
		p.setString(6, supplier);
		p.setString(7, altCode);
		p.setString(8, base + "/detail?id=" + key);
		p.addBatch();
	}

	private String dateOf(String title) {
		return jdbc.sql("SELECT published_on::text FROM catalog.book WHERE title = :title")
			.param("title", title)
			.query(String.class)
			.optional()
			.orElse(null);
	}

	/** The service keeps the redirected address (http → https, another port); the site is still the same. */
	@Test
	void sameSiteIgnoresSchemeAndPortOnly() {
		assertThat(CollectedCatalog.sameSite("https://elib.gangdong.ac.kr/FxLibrary", "http://elib.gangdong.ac.kr/FxLibrary"))
			.isTrue();
		assertThat(CollectedCatalog.sameSite("https://ebook.test:446", "http://ebook.test:81")).isTrue();
		assertThat(CollectedCatalog.sameSite("https://ebook.test/", "https://ebook.test")).isTrue();
		assertThat(CollectedCatalog.sameSite("https://lib.test?nav_code=a", "http://lib.test:8888?nav_code=a")).isTrue();
		assertThat(CollectedCatalog.sameSite("https://lib.test?nav_code=a", "http://lib.test?nav_code=b")).isFalse();
		assertThat(CollectedCatalog.sameSite("https://bookcube.test/FxLibrary", "https://bookcube.test")).isFalse();
		assertThat(CollectedCatalog.sameSite("https://other.test", "https://different.test")).isFalse();
		assertThat(CollectedCatalog.sameSite("https://other.test", null)).isFalse();
	}

	/** Detail URLs follow the service address: listed http on one port → the redirected https on another. */
	@Test
	void detailUrlsMoveOntoTheServiceOrigin() {
		String origin = CollectedCatalog.originOf("https://elib.gangdong.ac.kr:446");
		assertThat(origin).isEqualTo("https://elib.gangdong.ac.kr:446");
		assertThat(CollectedCatalog.onOrigin("http://elib.gangdong.ac.kr:9090/elibrary-front/content/contentView.ink?brcd=1&a=b",
				origin)).isEqualTo("https://elib.gangdong.ac.kr:446/elibrary-front/content/contentView.ink?brcd=1&a=b");
		assertThat(CollectedCatalog.onOrigin("http://elib.gangdong.ac.kr/FxLibrary/product/view/?num=H0001462",
				CollectedCatalog.originOf("https://elib.gangdong.ac.kr/FxLibrary")))
			.isEqualTo("https://elib.gangdong.ac.kr/FxLibrary/product/view/?num=H0001462");
		// already on the origin: unchanged
		assertThat(CollectedCatalog.onOrigin("https://kyobo.test:446/x?y=1", "https://kyobo.test:446"))
			.isEqualTo("https://kyobo.test:446/x?y=1");
	}

	private static void kind(PreparedStatement p, long library, String key, String title, String publishedOn,
			String publishedRaw, String contentKind, String contentType, String detailUrl, String author)
			throws Exception {
		p.setLong(1, library);
		p.setString(2, key);
		p.setString(3, title);
		p.setString(4, publishedOn);
		p.setString(5, publishedRaw);
		p.setString(6, contentKind);
		p.setString(7, contentType);
		p.setString(8, detailUrl);
		p.setString(9, author);
		p.setString(10, "나");
		p.addBatch();
	}

	private static void row(PreparedStatement p, long library, String key, String title, String author,
			String publisher, String date, String kind, String removedAt, String base) throws Exception {
		p.setLong(1, library);
		p.setString(2, key);
		p.setString(3, title);
		p.setString(4, author);
		p.setString(5, publisher);
		p.setString(6, date);
		p.setString(7, kind);
		p.setString(8, removedAt);
		p.setString(9, base == null ? null : base + "/detail?id=" + key);
		p.setString(10, null);
		p.addBatch();
	}

}
