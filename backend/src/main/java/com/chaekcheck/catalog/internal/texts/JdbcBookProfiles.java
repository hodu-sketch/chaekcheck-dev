package com.chaekcheck.catalog.internal.texts;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookProfiles;

/**
 * {@link BookProfiles} with {@code JdbcClient}. The profiles hold few distinct words (fields, genres, countries…), so
 * equal texts and lists are shared while reading (about 77,000 books, design-delta "서버 자원" 12~16 MB [추론]).
 */
@Component
class JdbcBookProfiles implements BookProfiles {

	private final JdbcClient jdbc;

	JdbcBookProfiles(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, Profile> all() {
		Map<String, String> words = new HashMap<>();
		Map<List<String>, List<String>> lists = new HashMap<>();
		Map<Long, Profile> profiles = new HashMap<>();
		jdbc.sql("""
				SELECT book_id, field, subfield, fiction, genres, country, audience, level, forms
				FROM catalog.book_profile""").query(rs -> {
			Boolean fiction = rs.getBoolean("fiction");
			if (rs.wasNull()) {
				fiction = null;
			}
			profiles.put(rs.getLong("book_id"),
					new Profile(shared(words, rs.getString("field")), shared(words, rs.getString("subfield")), fiction,
							shared(lists, words, rs.getArray("genres")), shared(words, rs.getString("country")),
							shared(words, rs.getString("audience")), shared(words, rs.getString("level")),
							shared(lists, words, rs.getArray("forms"))));
		});
		return profiles;
	}

	@Override
	public String version() {
		return String.valueOf(jdbc.sql("SELECT count(*) FROM catalog.book_profile").query(Long.class).single());
	}

	private static String shared(Map<String, String> words, String word) {
		return word == null ? null : words.computeIfAbsent(word, w -> w);
	}

	private static List<String> shared(Map<List<String>, List<String>> lists, Map<String, String> words, Array array)
			throws SQLException {
		if (array == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object value : (Object[]) array.getArray()) {
			values.add(shared(words, (String) value));
		}
		return lists.computeIfAbsent(List.copyOf(values), list -> list);
	}

}
