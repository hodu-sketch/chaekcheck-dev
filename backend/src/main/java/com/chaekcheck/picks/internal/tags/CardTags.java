package com.chaekcheck.picks.internal.tags;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.picks.BookTagTransfer;
import com.chaekcheck.picks.BookTagTransfer.Tag;

/** The tags cards show, read from {@code picks.book_tag} with the active dictionary codes (cycle 23, R9-5). */
@Component
public class CardTags {

	private final JdbcClient jdbc;

	CardTags(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** @return the card tags ({@link BookTagTransfer#forCard}) per book; books without an active tag are left out */
	public Map<Long, List<Tag>> byIds(Collection<Long> bookIds) {
		if (bookIds.isEmpty()) {
			return Map.of();
		}
		Map<Long, List<Tag>> all = new HashMap<>();
		jdbc.sql("""
				SELECT t.book_id, d.code, d.axis, d.name FROM picks.book_tag t
				JOIN picks.tag_dictionary d ON d.code = t.tag_code AND d.active
				WHERE t.book_id = ANY(CAST(:ids AS bigint[]))""")
			.param("ids", bookIds.stream().mapToLong(Long::longValue).toArray())
			.query((rs, n) -> {
				all.computeIfAbsent(rs.getLong(1), id -> new ArrayList<>())
					.add(new Tag(rs.getString(2), rs.getString(3), rs.getString(4)));
				return null;
			})
			.list();
		Map<Long, List<Tag>> cards = new HashMap<>();
		all.forEach((id, tags) -> {
			List<Tag> card = BookTagTransfer.forCard(tags);
			if (!card.isEmpty()) {
				cards.put(id, card);
			}
		});
		return cards;
	}

}
