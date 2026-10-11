package com.chaekcheck.library;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/**
 * library/014 on db-test (plan cycle 09 step 4, ADR-0071): names carry the body's name only, every library has a
 * vendor, every library but the portals a lending model, and (name, vendor, lending model, order) is unique.
 * Each constraint violation is its own test: a failed statement aborts the test transaction.
 */
@SpringBootTest
@Transactional
class LibrarySchemaCycle09Tests {

	/** A pattern of the vendor and lending labels removed from the end of names (docs/library-maintenance.md 4-13-1). */
	static final String LABEL_AT_END = "name ~ '\\((YES24|교보|북큐브|알라딘|구독|구독형|소장|소장형|대여|교보, [a-z0-9.-]+)\\)\\s*$'"
			+ " OR name ~ '대여형$'";

	@Autowired
	JdbcClient jdbc;

	// AC-7

	@Test
	void sameNameVendorLendingModelAndOrderIsRefused() {
		insert("테스트 같은 이름", "KYOBO_V1", "KYOBO", "'OWNED'", 1, "https://a.cycle09.test");
		assertViolation(() -> insert("테스트 같은 이름", "KYOBO_V1", "KYOBO", "'OWNED'", 1, "https://b.cycle09.test"));
	}

	@Test
	void twoPortalsWithTheSameNameAreRefused() {
		insert("테스트 포털", "ELIB_PORTAL_V1", "PORTAL", "NULL", 1, "https://a.cycle09.test/elib");
		assertViolation(() -> insert("테스트 포털", "ELIB_PORTAL_V1", "PORTAL", "NULL", 1, "https://b.cycle09.test/elib"));
	}

	@Test
	void onlyTheOrderDiffersGoesIn() {
		insert("테스트 같은 이름", "KYOBO_V1", "KYOBO", "'OWNED'", 1, "https://a.cycle09.test");
		insert("테스트 같은 이름", "KYOBO_V1", "KYOBO", "'OWNED'", 2, "https://b.cycle09.test");
		insert("테스트 같은 이름", "YES24_V2", "YES24", "'OWNED'", 1, "https://c.cycle09.test");
		assertThat(count("SELECT count(*) FROM library.library WHERE name = '테스트 같은 이름'")).isEqualTo(3);
	}

	@Test
	void disabledLibrariesCountForTheUniqueKeyToo() {
		long off = insert("테스트 꺼짐", "KYOBO_V1", "KYOBO", "'OWNED'", 1, "https://a.cycle09.test");
		jdbc.sql("UPDATE library.library SET enabled = false WHERE id = ?").param(off).update();
		assertViolation(() -> insert("테스트 꺼짐", "KYOBO_V1", "KYOBO", "'OWNED'", 1, "https://b.cycle09.test"));
	}

	// the CHECKs

	@Test
	void vendorOutsideTheSixIsRefused() {
		assertViolation(() -> jdbc.sql("UPDATE library.library SET vendor = 'RIDI' WHERE id = 1").update());
	}

	@Test
	void ebookWithoutVendorIsRefused() {
		assertViolation(() -> jdbc.sql("UPDATE library.library SET vendor = NULL WHERE id = 1").update());
	}

	@Test
	void portalWithALendingModelIsRefused() {
		assertViolation(() -> jdbc.sql("""
				UPDATE library.library SET lending_model = 'OWNED'
				WHERE id = (SELECT min(id) FROM library.library WHERE vendor = 'PORTAL')""").update());
	}

	@Test
	void nonPortalWithoutLendingModelIsRefused() {
		assertViolation(() -> jdbc.sql("UPDATE library.library SET lending_model = NULL WHERE id = 1").update());
	}

	@Test
	void orderZeroIsRefused() {
		assertViolation(() -> jdbc.sql("UPDATE library.library SET order_no = 0 WHERE id = 1").update());
	}

	// what 014 left (AC-1, AC-2, AC-3)

	@Test
	void everyLibraryHasAVendorAndAllButThePortalsALendingModel() {
		assertThat(count("SELECT count(*) FROM library.library WHERE vendor IS NULL")).isZero();
		assertThat(count("SELECT count(*) FROM library.library WHERE lending_model IS NULL AND vendor <> 'PORTAL'"))
			.isZero();
		assertThat(count("SELECT count(*) FROM library.library WHERE vendor = 'PORTAL'")).isEqualTo(7);
		assertThat(count("SELECT count(*) FROM library.library WHERE order_no <> 1")).isZero();
	}

	@Test
	void noNameEndsWithAVendorOrLendingLabel() {
		assertThat(count("SELECT count(*) FROM library.library WHERE " + LABEL_AT_END)).isZero();
	}

	/** The 45 names with brackets of their own stay (0단계 list, data/cycle09/paren-names-after.tsv). */
	@Test
	void bracketsInsideTheBodyNameStay() {
		assertThat(count("SELECT count(*) FROM library.library WHERE name LIKE '%(%'")).isEqualTo(45);
		assertThat(names(5, 404, 948, 2335)).containsExactly("5 광주시립도서관(경기) 전자도서관",
				"404 (주)HJ중공업 해모로 ebook 도서관", "948 SBS(에스비에스)", "2335 주례초등학교(부산)");
	}

	@Test
	void renamedLibraries() {
		assertThat(names(64, 76, 93, 386, 428, 429, 434, 456, 769, 895, 943, 977, 1002, 1149, 1575, 1576, 2257))
			.containsExactly("64 경상남도교육청 마산도서관", "76 서대문구립도서관", "93 양산시립도서관", "386 진천군립도서관",
					"428 국립창원대학교 거창캠퍼스", "429 국립창원대학교 남해캠퍼스", "434 경상국립대학교", "456 국립경국대학교",
					"769 강원대학교 강릉캠퍼스", "895 한경국립대학교", "943 경상남도교육청 양산도서관", "977 국립경국대학교",
					"1002 한국공학대학교", "1149 메리츠(증권&캐피탈)", "1575 완주동상초등학교", "1576 부여대왕초등학교",
					"2257 강원대학교 강릉캠퍼스");
	}

	@Test
	void subscriptionLendingModels() {
		assertThat(jdbc.sql("""
				SELECT id FROM library.library WHERE lending_model = 'SUBSCRIPTION' AND vendor = 'BOOKCUBE' ORDER BY id""")
			.query(Long.class)
			.list()).containsExactly(339L, 380L, 386L, 705L);
		assertThat(jdbc.sql("""
				SELECT id FROM library.library
				WHERE lending_model = 'SUBSCRIPTION' AND platform_type = 'KYOBO_SUBSCRIPTION_V1' ORDER BY id""")
			.query(Long.class)
			.list()).containsExactly(59L, 131L, 140L, 275L, 276L, 277L, 278L);
	}

	/**
	 * C5 (AC-8): the libraries HoldingTask reads as subscription are the same as before 014 (saved in 0단계 from the
	 * local db, data/cycle09/c5-before.txt).
	 */
	@Test
	void kyoboSubscriptionVerdictsStay() {
		assertThat(jdbc.sql("""
				SELECT id FROM library.library
				WHERE platform_type = 'KYOBO_SUBSCRIPTION_V1' AND lending_model IS DISTINCT FROM 'OWNED' ORDER BY id""")
			.query(Long.class)
			.list()).containsExactly(7L, 16L, 37L, 43L, 59L, 63L, 89L, 121L, 122L, 129L, 131L, 134L, 140L, 158L, 275L,
					276L, 277L, 278L);
	}

	private long insert(String name, String platform, String vendor, String lendingModel, int orderNo, String url) {
		return jdbc.sql("""
				INSERT INTO library.library (name, platform_type, vendor, lending_model, order_no, base_url, join_method)
				VALUES (:name, :platform, :vendor, %s, :orderNo, :url, 'test') RETURNING id""".formatted(lendingModel))
			.param("name", name)
			.param("platform", platform)
			.param("vendor", vendor)
			.param("orderNo", orderNo)
			.param("url", url)
			.query(Long.class)
			.single();
	}

	private List<String> names(long... ids) {
		return jdbc.sql("SELECT id || ' ' || name FROM library.library WHERE id IN (:ids) ORDER BY id")
			.param("ids", Arrays.stream(ids).boxed().toList())
			.query(String.class)
			.list();
	}

	private long count(String sql) {
		return jdbc.sql(sql).query(Long.class).single();
	}

	private static void assertViolation(Runnable statement) {
		assertThatThrownBy(statement::run).isInstanceOf(DataIntegrityViolationException.class);
	}

}
