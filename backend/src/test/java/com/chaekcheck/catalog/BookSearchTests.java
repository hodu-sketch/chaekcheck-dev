package com.chaekcheck.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.internal.normalize.CatalogText;
import com.chaekcheck.catalog.internal.normalize.Contributors;

/**
 * {@code /api/v1/books} against db-test with pg_bigm and a few synthetic books (spec AC-3~6, AC-12). Every test
 * switches the seeded libraries off and empties the catalog inside its transaction, so nothing depends on seeds or
 * an earlier import.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookSearchTests {

	static final long MAPO = 1114, SEOUL = 11;

	@Autowired
	MockMvcTester mvc;

	@Autowired
	JdbcClient jdbc;

	long open, openVisit, seoul, unverified;

	@BeforeEach
	void setUp() throws Exception {
		jdbc.sql("UPDATE library.library SET enabled = false").update();
		jdbc.sql("TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot,"
				+ " catalog.catalog_import").update();
		open = library("누구나", false);
		nationwide(open);
		openVisit = library("누구나 방문", true);
		nationwide(openVisit);
		seoul = library("서울", false);
		jdbc.sql("INSERT INTO library.eligibility_rule (library_id, basis, scope_type, scope_region_id)"
				+ " VALUES (?, 'RESIDENCE', 'REGION', ?)").params(seoul, SEOUL).update();
		unverified = library("미확인", false);
		long importId = jdbc.sql("INSERT INTO catalog.catalog_import (source, imported_at, book_count, holding_count,"
				+ " skipped_count) VALUES ('test', now(), 0, 0, 0) RETURNING id").query(Long.class).single();
		for (long id : List.of(open, openVisit, seoul, unverified)) {
			jdbc.sql("INSERT INTO catalog.catalog_library_snapshot (library_id, collected_at, import_id)"
					+ " VALUES (?, TIMESTAMPTZ '2026-09-29 12:00+09', ?)").params(id, importId).update();
		}
	}

	@Test
	void wordsInAnyOrderAndJoinedWordsGiveASubset() throws Exception { // AC-3
		long both = book("행동 경제학", "리처드 탈러 저", "리더스북", "2020-01-01", open);
		long reversed = book("경제학과 인간 행동", "김경제", "문학동네", "2019-01-01", open);
		long joined = book("행동경제학 입문", "이행동", "문학동네", "2018-01-01", open);
		book("행동", "로버트 M. 새폴스키 저/김명남 역", "문학동네", "2023-04-18", open);

		assertThat(ids("title=행동 경제학")).containsExactlyInAnyOrder(both, reversed, joined);
		assertThat(ids("title=행동경제학")).containsExactlyInAnyOrder(both, joined)
			.isSubsetOf(ids("title=행동 경제학"));
	}

	@Test
	void titleAndAuthorAreAnded() throws Exception { // AC-2 book part
		long behave = book("행동", "로버트 M. 새폴스키 저/김명남 역", "문학동네", "2023-04-18", open);
		book("행동의 심리학", "김철수", "문학동네", "2023-01-01", open);
		book("침팬지 비밀", "로버트 M. 새폴스키 저", "문학동네", "2011-01-01", open);

		assertThat(ids("title=행동&author=새폴스키")).containsExactly(behave);
	}

	@Test
	void exactTitleMatchesMainOrWholeTitle() throws Exception { // AC-4
		long behave = book("행동 : 인간의 최선과 최악", "새폴스키", "문학동네", "2023-04-18", open);
		book("행동경제학", "탈러", "리더스북", "2020-01-01", open);

		assertThat(ids("title=행동&exact=title")).containsExactly(behave);
		assertThat(ids("author=새 폴스키&exact=author")).containsExactly(behave);
		assertThat(ids("publisher=(주)문학동네&exact=publisher")).containsExactly(behave);
	}

	@Test
	void translatorsOnlyWithTheRole() throws Exception { // AC-5
		long translated = book("행동", "로버트 M. 새폴스키 저/양병찬 역", "문학동네", "2023-04-18", open);
		long drawn = book("그림책", "백희나 글/양병찬 그림", "책읽는곰", "2020-01-01", open);

		assertThat(ids("author=양병찬")).isEmpty();
		assertThat(ids("author=양병찬&roles=translator")).containsExactly(translated);
		assertThat(ids("author=양병찬&roles=translator,illustrator")).containsExactlyInAnyOrder(translated, drawn);
	}

	@Test
	void allWordsMustBeInOnePersonsName() throws Exception { // ADR-0032
		book("공저 책", "김가나 저/박다라 저", "가나출판", "2020-01-01", open);

		assertThat(ids("author=김가나 박다라")).isEmpty();
		assertThat(ids("author=가나 김")).hasSize(1);
	}

	@Test
	void defaultOrderIsTierThenExactThenLibrariesThenDate() throws Exception { // AC-6
		long needsCheck = book("사랑 A", "가", "가", "2025-01-01", unverified);
		long visitOnly = book("사랑 B", "가", "가", "2025-01-01", openVisit);
		long openOld = book("사랑 C", "가", "가", "2001-01-01", open);
		long openNew = book("사랑 D", "가", "가", "2024-01-01", open);
		long openTwo = book("사랑 E", "가", "가", "1999-01-01", open, unverified);
		long exact = book("사랑", "가", "가", "1990-01-01", open);

		assertThat(ids("title=사랑")).containsExactly(exact, openTwo, openNew, openOld, visitOnly, needsCheck);
		// with a region: my SIDO without a visit comes before anyone
		long mySido = book("사랑 F", "가", "가", "1980-01-01", seoul);
		assertThat(ids("title=사랑&residenceRegionIds=" + MAPO)).first().isEqualTo(mySido);
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&residenceRegionIds=" + MAPO)).bodyJson()
			.extractingPath("$.items[0].joinTier")
			.isEqualTo("MY_SIDO");
	}

	@Test
	void dateOrderPutsUnknownDatesLastAndFutureDatesAreNotShown() throws Exception { // AC-6
		long none = book("사랑 없음", "가", "가", null, open);
		long old = book("사랑 옛날", "가", "가", "2001-01-01", open);
		long recent = book("사랑 최근", "가", "가", "2024-01-01", open);

		assertThat(ids("title=사랑&sort=newest")).containsExactly(recent, old, none);
		assertThat(ids("title=사랑&sort=oldest")).containsExactly(old, recent, none);
	}

	@Test
	void scopeAndVisitLeaveOutBooksWithoutPlanLibraries() throws Exception { // AC-12
		long onlyUnverified = book("사랑 확인", "가", "가", "2020-01-01", unverified);
		long onlyVisit = book("사랑 방문", "가", "가", "2020-01-01", openVisit);
		long both = book("사랑 둘", "가", "가", "2020-01-01", open, unverified);

		assertThat(ids("title=사랑&scope=joinable")).containsExactlyInAnyOrder(onlyVisit, both);
		assertThat(ids("title=사랑&visit=none")).containsExactlyInAnyOrder(onlyUnverified, both);
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&scope=joinable")).bodyJson()
			.extractingPath("$.items[?(@.id == " + both + ")].holdingLibraryCount")
			.asArray()
			.containsExactly(1);
	}

	@Test
	void pagesAndCollectionDates() throws Exception {
		for (int i = 0; i < 25; i++) {
			book("사랑 " + i, "가", "가", "2020-01-01", open);
		}

		assertThat(mvc.get().uri("/api/v1/books?title=사랑")).bodyJson()
			.isLenientlyEqualTo("{\"page\":1,\"pageSize\":20,\"totalCount\":25,\"hasMore\":true,"
					+ "\"collectedFrom\":\"2026-09-29\",\"collectedTo\":\"2026-09-29\"}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&page=2&pageSize=20")).bodyJson()
			.isLenientlyEqualTo("{\"page\":2,\"totalCount\":25,\"hasMore\":false}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&page=3")).bodyJson()
			.isLenientlyEqualTo("{\"items\":[],\"totalCount\":25,\"hasMore\":false}");
	}

	@Test
	void oneBookWithItsPlanLibraries() throws Exception {
		long id = book("행동", "새폴스키", "문학동네", "2023-04-18", open, unverified);

		assertThat(mvc.get().uri("/api/v1/books/" + id)).bodyJson()
			.isLenientlyEqualTo("{\"title\":\"행동\",\"holdingLibraryCount\":2,\"libraries\":[{\"libraryId\":" + open
					+ ",\"collectedAt\":\"2026-09-29T12:00:00+09:00\"},{\"libraryId\":" + unverified + "}]}");
		assertThat(mvc.get().uri("/api/v1/books/" + id + "?scope=joinable")).bodyJson()
			.extractingPath("$.holdingLibraryCount")
			.isEqualTo(1);
		assertThat(mvc.get().uri("/api/v1/books/999999999")).hasStatus(404)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("book_not_found");
		assertThat(mvc.get().uri("/api/v1/books/abc")).hasStatus(404);
	}

	@Test
	void validationErrors() throws Exception {
		assertThat(mvc.get().uri("/api/v1/books")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"code\":\"validation_error\",\"errors\":[{\"field\":\"title\",\"code\":\"required\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=가")).hasStatus(400)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_query");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&title=행동")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"title\",\"code\":\"duplicate\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&exact=isbn")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"exact\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&roles=other")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"roles\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&sort=title")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"sort\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&scope=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"scope\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&pageSize=21")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"pageSize\",\"code\":\"invalid_format\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&page=0")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"page\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&residenceRegionIds=11")).hasStatus(422)
			.bodyJson()
			.extractingPath("$.code")
			.isEqualTo("invalid_region");
		// the condition is checked before the other parameters
		assertThat(mvc.get().uri("/api/v1/books?sort=title")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"title\",\"code\":\"required\"}]}");
	}

	@Test
	void chosenLibraryBooksComeFirstInBothScopes() throws Exception { // cycle 06 ADR-0050
		long needsCheck = book("사랑 A", "가", "가", "2025-01-01", unverified);
		long openBook = book("사랑", "가", "가", "1990-01-01", open);
		String chosen = "&libraryIds=" + unverified;

		assertThat(ids("title=사랑")).containsExactly(openBook, needsCheck);
		assertThat(ids("title=사랑" + chosen)).containsExactly(needsCheck, openBook);
		assertThat(ids("title=사랑&scope=joinable" + chosen)).containsExactly(needsCheck, openBook);
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&residenceRegionIds=" + MAPO + chosen)).bodyJson()
			.extractingPath("$.items[*].joinTier")
			.asArray()
			.containsExactly("CHOSEN", "OPEN");
		assertThat(mvc.get().uri("/api/v1/books/" + needsCheck + "?scope=joinable" + chosen)).bodyJson()
			.extractingPath("$.holdingLibraryCount")
			.isEqualTo(1);
	}

	@Test
	void libraryIdsValidation() throws Exception {
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&libraryIds=1,x")).hasStatus(400)
			.bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"invalid_format\"}]}");
		String many = String.join(",", java.util.stream.LongStream.rangeClosed(1, 21).mapToObj(Long::toString).toList());
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&libraryIds=" + many)).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\",\"code\":\"too_many\"}]}");
		// order: visit, then libraryIds, then regions; unknown ids are no error (AC-10)
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&visit=x&libraryIds=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"visit\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&libraryIds=x&residenceRegionIds=x")).bodyJson()
			.isLenientlyEqualTo("{\"errors\":[{\"field\":\"libraryIds\"}]}");
		assertThat(mvc.get().uri("/api/v1/books?title=사랑&libraryIds=999999999")).hasStatusOk();
	}

	@Test
	void likeWildcardsInWordsAreLiteral() throws Exception {
		book("100% 사랑", "가", "가", "2020-01-01", open);
		book("1000 사랑", "가", "가", "2020-01-01", open);

		assertThat(ids("title=0% 사랑")).hasSize(1);
		assertThat(ids("title=__")).isEmpty();
	}

	private List<Long> ids(String query) throws Exception {
		MvcTestResult result = mvc.get().uri("/api/v1/books?" + query).exchange();
		assertThat(result).hasStatusOk();
		List<Number> ids = JsonPath.read(result.getResponse().getContentAsString(StandardCharsets.UTF_8),
				"$.items[*].id");
		return ids.stream().map(Number::longValue).toList();
	}

	private long library(String name, boolean visit) {
		return jdbc.sql("""
				INSERT INTO library.library (name, platform_type, base_url, join_method, visit_required)
				VALUES (:name, 'KYOBO_V1', :url, 'test', :visit) RETURNING id""")
			.param("name", "테스트 " + name)
			.param("url", "https://" + Integer.toHexString(name.hashCode()) + ".test")
			.param("visit", visit)
			.query(Long.class)
			.single();
	}

	private void nationwide(long libraryId) {
		jdbc.sql("INSERT INTO library.eligibility_rule (library_id, basis, scope_type) VALUES (?, 'RESIDENCE', 'NATIONWIDE')")
			.param(libraryId)
			.update();
	}

	/** A book held by the given libraries, normalized with the importer's rules. */
	private long book(String title, String author, String publisher, String date, long... libraries) {
		long id = jdbc.sql("""
				INSERT INTO catalog.book (title, author_display, publisher, title_norm, main_title_norm, publisher_norm,
				  published_on, content_type)
				VALUES (:title, :author, :publisher, :titleNorm, :main, :publisherNorm, CAST(:date AS date), 'EBOOK')
				RETURNING id""")
			.param("title", title)
			.param("author", author)
			.param("publisher", publisher)
			.param("titleNorm", CatalogText.titleNorm(title))
			.param("main", CatalogText.mainTitleNorm(title))
			.param("publisherNorm", CatalogText.publisherNorm(publisher))
			.param("date", date)
			.query(Long.class)
			.single();
		for (Contributors.Contributor c : Contributors.parse(author)) {
			jdbc.sql("""
					INSERT INTO catalog.book_contributor (book_id, name, alias, name_norm, name_exact, role, position)
					VALUES (?, ?, ?, ?, ?, ?, ?)""")
				.params(id, c.name(), c.alias(), c.nameNorm(), c.nameExact(), c.role().name(), c.position())
				.update();
		}
		for (long library : libraries) {
			jdbc.sql("""
					INSERT INTO catalog.holding (book_id, library_id, platform, item_key, detail_url, content_type,
					  first_seen_at)
					VALUES (?, ?, 'KYOBO_V1', ?, 'https://example.test/detail', 'EBOOK', now())""")
				.params(id, library, "item-" + id)
				.update();
		}
		return id;
	}

}
