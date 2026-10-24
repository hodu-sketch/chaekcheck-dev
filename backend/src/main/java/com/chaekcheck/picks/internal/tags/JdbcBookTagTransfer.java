package com.chaekcheck.picks.internal.tags;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.picks.BookTagTransfer;

/** {@code picks.tag_dictionary} and {@code picks.book_tag} with {@code JdbcClient} (cycle 23). */
@Component
class JdbcBookTagTransfer implements BookTagTransfer {

	private final JdbcClient jdbc;

	JdbcBookTagTransfer(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public int upsertDictionary(Collection<DictionaryRow> rows) {
		Set<String> codes = new LinkedHashSet<>();
		for (DictionaryRow row : rows) {
			codes.add(row.code());
			jdbc.sql("""
					INSERT INTO picks.tag_dictionary (code, axis, name, thema_code, active)
					VALUES (:code, :axis, :name, :thema, true)
					ON CONFLICT (code) DO UPDATE SET axis = EXCLUDED.axis, name = EXCLUDED.name,
					  thema_code = EXCLUDED.thema_code, active = true""")
				.param("code", row.code())
				.param("axis", row.axis())
				.param("name", row.name())
				.param("thema", row.themaCode())
				.update();
		}
		return jdbc.sql("UPDATE picks.tag_dictionary SET active = false WHERE active AND NOT code = ANY(CAST(:codes AS text[]))")
			.param("codes", codes.toArray(String[]::new))
			.update();
	}

	@Override
	@Transactional
	public int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn) {
		Set<String> wanted = new LinkedHashSet<>(codes);
		List<String> known = wanted.isEmpty() ? List.of()
				: jdbc.sql("SELECT code FROM picks.tag_dictionary WHERE code = ANY(CAST(:codes AS text[]))")
					.param("codes", wanted.toArray(String[]::new))
					.query(String.class)
					.list();
		jdbc.sql("DELETE FROM picks.book_tag WHERE book_id = ?").param(bookId).update();
		for (String code : known) {
			jdbc.sql("INSERT INTO picks.book_tag (book_id, tag_code, made_on) VALUES (?, ?, ?)")
				.params(bookId, code, madeOn)
				.update();
		}
		return wanted.size() - known.size();
	}

}
