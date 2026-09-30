package com.chaekcheck.catalog.internal.importer;

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

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.LibraryRef;
import com.chaekcheck.library.PlatformType;

/**
 * One import from a tiny SQLite and TTL written by the test into db-test (handoff 5단계 테스트): grouping ①②③,
 * only-one-record rule, filters (AC-10), future dates, COPY result. The catalog tables are emptied afterwards.
 */
@SpringBootTest
class CatalogImporterTests {

	static final String KYOBO_BASE = "https://kyobo.test:446", YES24_BASE = "https://yes24.test",
			PORTAL_BASE = "https://portal.test/elib";

	@Autowired
	DataSource dataSource;

	@Autowired
	JdbcClient jdbc;

	@TempDir
	Path dir;

	LibraryCatalog libraryCatalog = mock(LibraryCatalog.class);

	@BeforeEach
	void setUp() {
		given(libraryCatalog.enabledLibraryRefs()).willReturn(List.of(new LibraryRef(1, KYOBO_BASE, PlatformType.KYOBO_V1),
				new LibraryRef(2, YES24_BASE, PlatformType.YES24_V2), new LibraryRef(3, "https://other.test",
						PlatformType.KYOBO_V1),
				new LibraryRef(4, PORTAL_BASE, PlatformType.ELIB_PORTAL_V1),
				new LibraryRef(5, "https://portal2.test/elib", PlatformType.ELIB_PORTAL_V1)));
	}

	@AfterEach
	void empty() {
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot,"
				+ " catalog.catalog_import").update();
	}

	@Test
	void importsGroupsAndSkips() throws Exception {
		Path sqlite = dir.resolve("catalog.sqlite");
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
						+ " (100001, 'https://bookcube.test', '2026-09-30T01:00:00+09:00')");
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
				row(p, 100001, "S7", "북큐브", "가", "나", null, "EBOOK", null, "https://bookcube.test");
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
		Clock clock = Clock.fixed(Instant.parse("2026-09-30T03:00:00Z"), ZoneOffset.UTC);

		ImportReport report = new CatalogImporter(libraryCatalog, dataSource, clock).run(sqlite, ttl);

		assertThat(report.skipped).containsExactlyInAnyOrderEntriesOf(Map.of("kind_audiobook", 1L, "kind_video_book",
				1L, "removed", 1L, "detail_host_mismatch", 1L, "no_detail_url", 1L, "library_not_in_service", 2L));
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
		assertThat(jdbc.sql("SELECT book_count, holding_count, skipped_count FROM catalog.catalog_import")
			.query()
			.singleRow()).containsEntry("book_count", 8).containsEntry("holding_count", 11).containsEntry("skipped_count", 7);

		// a second import replaces the data
		new CatalogImporter(libraryCatalog, dataSource, clock).run(sqlite, null);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.catalog_import").query(Long.class).single()).isEqualTo(1);
		assertThat(jdbc.sql("SELECT count(*) FROM catalog.book").query(Long.class).single()).isEqualTo(9);
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
