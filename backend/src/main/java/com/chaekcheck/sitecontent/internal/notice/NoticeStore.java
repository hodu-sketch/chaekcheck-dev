package com.chaekcheck.sitecontent.internal.notice;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.sitecontent.internal.VersionDate;

/**
 * Reads {@code sitecontent.notice} and {@code notice_version}. A notice is visible when it is not hidden and its
 * version 1 is in effect on {@code today}; its posting day is version 1's effective day and its title the current
 * version's (C4). Order: posting day, newest first, then the higher number first.
 */
@Component
public class NoticeStore {

	private static final String VISIBLE = """
			FROM sitecontent.notice n
			JOIN sitecontent.notice_version first ON first.notice_id = n.id AND first.version_no = 1
			WHERE NOT n.hidden AND first.effective_on <= :today""";

	private final JdbcClient jdbc;

	NoticeStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Visible notices that are not pinned. */
	public int countUnpinned(LocalDate today) {
		return jdbc.sql("SELECT count(*) " + VISIBLE + " AND NOT n.pinned")
			.param("today", today)
			.query(Integer.class)
			.single();
	}

	public List<Row> unpinned(LocalDate today, int limit, int offset) {
		return rows(" AND NOT n.pinned", today, limit, offset);
	}

	public List<Row> pinned(LocalDate today) {
		return rows(" AND n.pinned", today, Integer.MAX_VALUE, 0);
	}

	private List<Row> rows(String condition, LocalDate today, int limit, int offset) {
		return jdbc.sql("""
				SELECT n.id, first.effective_on AS posted_on,
				  (SELECT v.title FROM sitecontent.notice_version v
				   WHERE v.notice_id = n.id AND v.effective_on <= :today
				   ORDER BY v.version_no DESC LIMIT 1) AS title
				""" + VISIBLE + condition + """

				ORDER BY first.effective_on DESC, n.id DESC
				LIMIT :limit OFFSET :offset""")
			.param("today", today)
			.param("limit", limit)
			.param("offset", offset)
			.query((rs, i) -> new Row(rs.getLong("id"), rs.getString("title"),
					rs.getObject("posted_on", LocalDate.class)))
			.list();
	}

	public boolean isVisible(long noticeId, LocalDate today) {
		return jdbc.sql("SELECT count(*) " + VISIBLE + " AND n.id = :id")
			.param("today", today)
			.param("id", noticeId)
			.query(Integer.class)
			.single() > 0;
	}

	/** Versions in effect, highest number first; the first is the current one. */
	public List<VersionDate> versions(long noticeId, LocalDate today) {
		return jdbc.sql("""
				SELECT version_no, effective_on FROM sitecontent.notice_version
				WHERE notice_id = :id AND effective_on <= :today
				ORDER BY version_no DESC""")
			.param("id", noticeId)
			.param("today", today)
			.query((rs, i) -> new VersionDate(rs.getInt("version_no"), rs.getObject("effective_on", LocalDate.class)))
			.list();
	}

	public Optional<Version> version(long noticeId, int versionNo, LocalDate today) {
		return jdbc.sql("""
				SELECT version_no, effective_on, title, body FROM sitecontent.notice_version
				WHERE notice_id = :id AND version_no = :version AND effective_on <= :today""")
			.param("id", noticeId)
			.param("version", versionNo)
			.param("today", today)
			.query((rs, i) -> new Version(rs.getInt("version_no"), rs.getObject("effective_on", LocalDate.class),
					rs.getString("title"), rs.getString("body")))
			.optional();
	}

	/** A line of the list. */
	public record Row(long id, String title, LocalDate postedOn) {
	}

	public record Version(int number, LocalDate effectiveOn, String title, String body) {
	}

}
