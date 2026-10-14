package com.chaekcheck.sitecontent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;

/** The sitecontent tables' rules and the seed (plan cycle 16 steps 1-2, design-delta "데이터"; AC-12). */
@SpringBootTest
@Transactional
class SitecontentSchemaTests {

	@Autowired
	JdbcClient jdbc;

	@Test
	void tablesExist() {
		assertThat(jdbc.sql("""
				SELECT table_name FROM information_schema.tables WHERE table_schema = 'sitecontent' ORDER BY 1""")
			.query(String.class)
			.list()).containsExactly("document_version", "notice", "notice_version");
	}

	@Test
	void seedHasVersionOneOfTheFourDocuments() {
		assertThat(jdbc.sql("""
				SELECT document_key || ':' || version_no || ':' || effective_on FROM sitecontent.document_version
				ORDER BY document_key""").query(String.class).list())
			.containsExactly("ABOUT:1:2026-10-04", "PRIVACY_COLLECTION:1:2026-10-04", "PRIVACY_POLICY:1:2026-10-04",
					"TERMS_OF_SERVICE:1:2026-10-04");
		assertThat(jdbc.sql("""
				SELECT jsonb_array_length(consent_items) FROM sitecontent.document_version
				WHERE document_key = 'PRIVACY_COLLECTION'""").query(Integer.class).single()).isEqualTo(4);
		assertThat(body("ABOUT")).contains("<chaekcheck9104@gmail.com>"); // AC-12
		for (String key : new String[] { "TERMS_OF_SERVICE", "PRIVACY_POLICY", "PRIVACY_COLLECTION" }) {
			assertThat(body(key)).as(key).startsWith("이 문서는 검토 전 자리 글입니다"); // R8-4
		}
		assertThat(body("PRIVACY_POLICY")).contains("## 처리하는 개인정보와 목적", "카카오 회원 식별 번호")
			.doesNotContain("네이버");
		assertThat(jdbc.sql("SELECT count(*) FROM sitecontent.notice").query(Integer.class).single()).isZero();
	}

	@Test
	void validRowsAreAccepted() {
		long notice = notice();
		noticeVersion(notice, "1", "'제목'", "'본문'");
		noticeVersion(notice, "2", "'제목'", "'본문'");
		document("'TERMS_OF_SERVICE'", "2", "'바뀐 점'", "NULL");
		document("'ABOUT'", "2", "NULL", "NULL");
		document("'PRIVACY_COLLECTION'", "2", "NULL", "'[1, 2, 3, 4]'");
	}

	@Test
	void noticeVersionRulesHold() {
		long notice = notice();
		violates(() -> noticeVersion(notice, "0", "'제목'", "'본문'"));
		violates(() -> noticeVersion(notice, "1", "'  '", "'본문'"));
		violates(() -> noticeVersion(notice, "1", "'제목'", "' \n '"));
		violates(() -> noticeVersion(notice + 1000, "1", "'제목'", "'본문'"));
	}

	@Test
	void duplicateNoticeVersionIsRejected() {
		long notice = notice();
		noticeVersion(notice, "1", "'제목'", "'본문'");
		violates(() -> noticeVersion(notice, "1", "'제목'", "'본문'"));
	}

	@Test
	void documentRulesHold() {
		violates(() -> document("'NOTICE'", "2", "NULL", "NULL"));
		violates(() -> document("'TERMS_OF_SERVICE'", "0", "NULL", "NULL"));
		violates(() -> document("'ABOUT'", "2", "'바뀐 점'", "NULL"));
		violates(() -> document("'PRIVACY_COLLECTION'", "2", "NULL", "NULL"));
		violates(() -> document("'PRIVACY_POLICY'", "2", "NULL", "'[1, 2, 3, 4]'"));
		violates(() -> document("'PRIVACY_COLLECTION'", "2", "NULL", "'[1, 2, 3]'"));
		violates(() -> document("'PRIVACY_COLLECTION'", "2", "NULL", "'[1, 2, 3, 4, 5]'"));
		violates(() -> document("'PRIVACY_COLLECTION'", "2", "NULL", "'{\"a\": 1}'"));
		violates(() -> jdbc.sql("""
				INSERT INTO sitecontent.document_version (document_key, version_no, effective_on, body)
				VALUES ('ABOUT', 2, DATE '2026-10-04', '   ')""").update());
	}

	@Test
	void duplicateDocumentVersionIsRejected() {
		violates(() -> document("'TERMS_OF_SERVICE'", "1", "NULL", "NULL"));
	}

	private String body(String key) {
		return jdbc.sql("SELECT body FROM sitecontent.document_version WHERE document_key = ? AND version_no = 1")
			.param(key)
			.query(String.class)
			.single();
	}

	private long notice() {
		return jdbc.sql("INSERT INTO sitecontent.notice DEFAULT VALUES RETURNING id").query(Long.class).single();
	}

	private void noticeVersion(long notice, String number, String title, String body) {
		jdbc.sql("INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body) VALUES ("
				+ notice + ", " + number + ", DATE '2026-10-14', " + title + ", " + body + ")").update();
	}

	private void document(String key, String number, String changeSummary, String consentItems) {
		jdbc.sql("""
				INSERT INTO sitecontent.document_version
				  (document_key, version_no, effective_on, body, change_summary, consent_items)
				VALUES (%s, %s, DATE '2026-10-14', '본문', %s, %s)""".formatted(key, number, changeSummary, consentItems))
			.update();
	}

	/** Behind a savepoint, so the test transaction stays usable for the next statement. */
	private void violates(Runnable insert) {
		jdbc.sql("SAVEPOINT violation").update();
		try {
			assertThatThrownBy(insert::run).isInstanceOf(DataIntegrityViolationException.class);
		}
		finally {
			jdbc.sql("ROLLBACK TO SAVEPOINT violation").update();
		}
	}

}
