package com.chaekcheck.sitecontent.internal.document;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.sitecontent.internal.VersionDate;

/**
 * Reads {@code sitecontent.document_version}. Only versions in effect on {@code today} (effective day on or before it)
 * are ever returned, so a future version shows nowhere before its day (C3).
 */
@Component
public class DocumentStore {

	private final JdbcClient jdbc;

	DocumentStore(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Versions in effect, highest number first; the first is the current one. */
	public List<VersionDate> versions(String documentKey, LocalDate today) {
		return jdbc.sql("""
				SELECT version_no, effective_on FROM sitecontent.document_version
				WHERE document_key = :key AND effective_on <= :today
				ORDER BY version_no DESC""")
			.param("key", documentKey)
			.param("today", today)
			.query((rs, i) -> new VersionDate(rs.getInt("version_no"), rs.getObject("effective_on", LocalDate.class)))
			.list();
	}

	public Optional<Version> version(String documentKey, int versionNo, LocalDate today) {
		return jdbc.sql("""
				SELECT version_no, effective_on, body, change_summary, consent_items::text AS consent_items
				FROM sitecontent.document_version
				WHERE document_key = :key AND version_no = :version AND effective_on <= :today""")
			.param("key", documentKey)
			.param("version", versionNo)
			.param("today", today)
			.query((rs, i) -> new Version(rs.getInt("version_no"), rs.getObject("effective_on", LocalDate.class),
					rs.getString("body"), rs.getString("change_summary"), rs.getString("consent_items")))
			.optional();
	}

	/** One version; {@code consentItemsJson} is the jsonb array as text, or null. */
	public record Version(int number, LocalDate effectiveOn, String body, String changeSummary,
			String consentItemsJson) {
	}

}
