package com.chaekcheck.catalog.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookAuthors;

/**
 * Reads {@code catalog.book.author_display} by id (cycle 24). Empty author texts count as none.
 */
@Component
class JdbcBookAuthors implements BookAuthors {

	private final JdbcClient jdbc;

	JdbcBookAuthors(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, String> byIds(Collection<Long> bookIds) {
		Set<Long> ids = new LinkedHashSet<>();
		for (Long id : bookIds) {
			if (id != null) {
				ids.add(id);
			}
		}
		if (ids.isEmpty()) {
			return Map.of();
		}
		Map<Long, String> authors = new HashMap<>();
		jdbc.sql("""
				SELECT id, author_display FROM catalog.book
				WHERE id = ANY(CAST(:ids AS bigint[])) AND nullif(btrim(author_display), '') IS NOT NULL""")
			.param("ids", ids.toArray(Long[]::new))
			.query(rs -> {
				authors.put(rs.getLong("id"), rs.getString("author_display"));
			});
		return Map.copyOf(authors);
	}

}
