package com.chaekcheck.catalog.internal.texts;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookTexts;
import com.chaekcheck.catalog.CoverUrls;

/** {@link BookTexts} with {@code JdbcClient}: one query a call, nothing kept in memory. */
@Component
class JdbcBookTexts implements BookTexts {

	private final JdbcClient jdbc;

	private final CoverUrls coverUrls;

	JdbcBookTexts(JdbcClient jdbc, CoverUrls coverUrls) {
		this.jdbc = jdbc;
		this.coverUrls = coverUrls;
	}

	@Override
	public Map<Long, String> summaries(Collection<Long> bookIds) {
		return texts("SELECT book_id, summary AS text FROM catalog.book_summary WHERE book_id = ANY(CAST(:ids AS bigint[]))",
				bookIds);
	}

	@Override
	public Map<Long, String> coverUrls(Collection<Long> bookIds) {
		return urls(texts(
				"SELECT book_id, file_key AS text FROM catalog.book_cover WHERE book_id = ANY(CAST(:ids AS bigint[]))",
				bookIds));
	}

	@Override
	public Map<Long, String> listCoverUrls(Collection<Long> bookIds) {
		// ① the edition's own cover, else ② another edition of the same work: the same main title (its "C" index) and an
		// AUTHOR or UNKNOWN name shared, the latest published first, then the one held by the most libraries (ADR-0164)
		return urls(texts("""
				SELECT w.id AS book_id, coalesce(own.file_key, other.file_key) AS text
				FROM unnest(CAST(:ids AS bigint[])) AS w (id)
				LEFT JOIN catalog.book_cover own ON own.book_id = w.id
				LEFT JOIN LATERAL (
				  SELECT c.file_key
				  FROM catalog.book b
				  JOIN catalog.book o ON o.main_title_norm COLLATE "C" = b.main_title_norm COLLATE "C" AND o.id <> b.id
				  JOIN catalog.book_cover c ON c.book_id = o.id
				  WHERE b.id = w.id AND own.book_id IS NULL AND b.main_title_norm <> ''
				    AND EXISTS (
				      SELECT 1 FROM catalog.book_contributor bc
				      JOIN catalog.book_contributor oc ON oc.name_exact COLLATE "C" = bc.name_exact COLLATE "C"
				      WHERE bc.book_id = b.id AND oc.book_id = o.id AND bc.role IN ('AUTHOR', 'UNKNOWN')
				        AND oc.role IN ('AUTHOR', 'UNKNOWN') AND bc.name_exact <> '')
				  ORDER BY o.published_on DESC NULLS LAST, cardinality(o.library_ids) DESC, o.id
				  LIMIT 1) other ON true
				WHERE coalesce(own.file_key, other.file_key) IS NOT NULL""", bookIds));
	}

	@Override
	public Map<Long, List<Tag>> tags(Collection<Long> bookIds) {
		Map<Long, List<Tag>> found = new HashMap<>();
		Long[] ids = distinct(bookIds);
		if (ids.length == 0) {
			return found;
		}
		jdbc.sql("""
				SELECT t.book_id, d.code, d.axis, d.name FROM catalog.book_tag t
				JOIN catalog.tag_dictionary d ON d.code = t.tag_code AND d.active
				WHERE t.book_id = ANY(CAST(:ids AS bigint[]))
				ORDER BY t.book_id, d.code""")
			.param("ids", ids)
			.query(rs -> {
				found.computeIfAbsent(rs.getLong("book_id"), id -> new ArrayList<>())
					.add(new Tag(rs.getString("code"), rs.getString("axis"), rs.getString("name")));
			});
		found.replaceAll((id, tags) -> List.copyOf(tags));
		return found;
	}

	private Map<Long, String> urls(Map<Long, String> keys) {
		Map<Long, String> urls = new HashMap<>();
		keys.forEach((id, key) -> {
			String url = coverUrls.of(key);
			if (url != null) {
				urls.put(id, url);
			}
		});
		return urls;
	}

	private Map<Long, String> texts(String sql, Collection<Long> bookIds) {
		Map<Long, String> found = new HashMap<>();
		Long[] ids = distinct(bookIds);
		if (ids.length == 0) {
			return found;
		}
		jdbc.sql(sql).param("ids", ids).query(rs -> {
			found.put(rs.getLong("book_id"), rs.getString("text"));
		});
		return found;
	}

	static Long[] distinct(Collection<Long> bookIds) {
		Set<Long> ids = new LinkedHashSet<>(bookIds);
		ids.remove(null);
		return ids.toArray(Long[]::new);
	}

}
