package com.chaekcheck.catalog.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;

/**
 * Reads {@code catalog.book} by its UNIQUE {@code book_isbn13_key}, and by title key and first author (ADR-0090).
 */
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

	/**
	 * Narrows by the first author first ({@code book_contributor_name_exact}, exact), then compares title keys in Java:
	 * the key drops symbols that {@code title_norm} keeps, so a pg_bigm search on it could miss a book.
	 */
	@Override
	public List<TitleAuthorBook> byTitleAndAuthor(Collection<TitleAuthor> queries) {
		Map<String, List<Keyed>> byName = new HashMap<>();
		Set<TitleAuthor> seen = new HashSet<>();
		for (TitleAuthor query : queries) {
			if (query == null || !seen.add(query)) {
				continue;
			}
			String key = CatalogText.pickTitleKey(query.title());
			String name = Contributors.firstNameExact(query.author());
			if (key.isEmpty() || name == null) {
				continue;
			}
			byName.computeIfAbsent(name, n -> new ArrayList<>()).add(new Keyed(query, key));
		}
		if (byName.isEmpty()) {
			return List.of();
		}
		List<TitleAuthorBook> found = new ArrayList<>();
		Set<Hit> added = new HashSet<>();
		jdbc.sql("""
				SELECT DISTINCT c.name_exact, b.id, b.title, b.library_ids
				FROM catalog.book_contributor c JOIN catalog.book b ON b.id = c.book_id
				WHERE c.name_exact COLLATE "C" = ANY(CAST(:names AS text[]))""")
			.param("names", byName.keySet().toArray(String[]::new))
			.query(rs -> {
				List<Keyed> wanted = byName.get(rs.getString("name_exact"));
				if (wanted == null) {
					return;
				}
				String bookKey = CatalogText.pickTitleKey(rs.getString("title"));
				long bookId = rs.getLong("id");
				for (Keyed query : wanted) {
					if (query.key().equals(bookKey) && added.add(new Hit(query.query(), bookId))) {
						found.add(new TitleAuthorBook(query.query(), bookId, longs(rs.getArray("library_ids"))));
					}
				}
			});
		return found;
	}

	private record Keyed(TitleAuthor query, String key) {
	}

	private record Hit(TitleAuthor query, long bookId) {
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
