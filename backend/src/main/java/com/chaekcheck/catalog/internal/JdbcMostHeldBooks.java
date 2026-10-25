package com.chaekcheck.catalog.internal;

import java.sql.Array;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.MostHeldBooks;

/** Reads the most held books from {@code catalog.book}. Empty author texts count as none. */
@Component
class JdbcMostHeldBooks implements MostHeldBooks {

	private final JdbcClient jdbc;

	JdbcMostHeldBooks(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<HeldBook> top(int count) {
		if (count < 1) {
			return List.of();
		}
		return jdbc.sql("""
				SELECT id, title, nullif(btrim(author_display), '') AS author, library_ids FROM catalog.book
				ORDER BY cardinality(library_ids) DESC, id
				LIMIT :count""")
			.param("count", count)
			.query((rs, n) -> {
				Array array = rs.getArray("library_ids");
				Long[] ids = (Long[]) array.getArray();
				long[] libraryIds = new long[ids.length];
				for (int i = 0; i < ids.length; i++) {
					libraryIds[i] = ids[i];
				}
				return new HeldBook(rs.getLong("id"), rs.getString("title"), rs.getString("author"), libraryIds);
			})
			.list();
	}

}
