package com.chaekcheck.catalog.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;

/** Reads {@code catalog.book} by its UNIQUE {@code book_isbn13_key} (ADR-0074). */
@Component
class JdbcBookLookup implements BookLookup {

	private static final Pattern ISBN13 = Pattern.compile("97[89][0-9]{10}");

	private final JdbcClient jdbc;

	JdbcBookLookup(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<IsbnBook> byIsbn13(Collection<String> isbn13s) {
		Set<String> isbns = new LinkedHashSet<>();
		for (String isbn : isbn13s) {
			if (isbn != null && ISBN13.matcher(isbn).matches()) {
				isbns.add(isbn);
			}
		}
		if (isbns.isEmpty()) {
			return List.of();
		}
		return jdbc.sql("SELECT id, isbn13, library_ids FROM catalog.book WHERE isbn13 = ANY(CAST(:isbns AS text[]))")
			.param("isbns", isbns.toArray(String[]::new))
			.query((rs, rowNum) -> new IsbnBook(rs.getString("isbn13"), rs.getLong("id"),
					longs(rs.getArray("library_ids"))))
			.list();
	}

	private static long[] longs(Array array) throws SQLException {
		if (array == null) {
			return new long[0];
		}
		Object[] values = (Object[]) array.getArray();
		long[] ids = new long[values.length];
		for (int i = 0; i < values.length; i++) {
			ids[i] = ((Number) values[i]).longValue();
		}
		return ids;
	}

}
