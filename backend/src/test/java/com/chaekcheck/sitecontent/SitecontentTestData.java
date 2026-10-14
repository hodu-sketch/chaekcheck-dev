package com.chaekcheck.sitecontent;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.sitecontent.internal.Today;

/** Notices and document versions written in the test transaction, and a fixed "today" (Asia/Seoul). */
final class SitecontentTestData {

	/** The fixed today of these tests; the seed's version 1 took effect on 2026-10-04. */
	static final LocalDate TODAY = LocalDate.parse("2026-10-14");

	private final JdbcClient jdbc;

	SitecontentTestData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	static void fixToday(Today today) {
		ZoneId seoul = ZoneId.of("Asia/Seoul");
		today.setClock(Clock.fixed(TODAY.atTime(12, 0).atZone(seoul).toInstant(), seoul));
	}

	static void resetToday(Today today) {
		today.setClock(Clock.systemUTC());
	}

	long notice(String title, LocalDate postedOn) {
		return notice(title, postedOn, false, false);
	}

	long notice(String title, LocalDate postedOn, boolean pinned, boolean hidden) {
		long id = jdbc.sql("INSERT INTO sitecontent.notice (pinned, hidden) VALUES (?, ?) RETURNING id")
			.params(pinned, hidden)
			.query(Long.class)
			.single();
		noticeVersion(id, 1, postedOn, title, "본문 " + title);
		return id;
	}

	void noticeVersion(long noticeId, int number, LocalDate effectiveOn, String title, String body) {
		jdbc.sql("""
				INSERT INTO sitecontent.notice_version (notice_id, version_no, effective_on, title, body)
				VALUES (?, ?, ?, ?, ?)""").params(noticeId, number, effectiveOn, title, body).update();
	}

	void documentVersion(String key, int number, LocalDate effectiveOn, String body, String changeSummary) {
		jdbc.sql("""
				INSERT INTO sitecontent.document_version (document_key, version_no, effective_on, body, change_summary)
				VALUES (?, ?, ?, ?, ?)""").params(key, number, effectiveOn, body, changeSummary).update();
	}

}
