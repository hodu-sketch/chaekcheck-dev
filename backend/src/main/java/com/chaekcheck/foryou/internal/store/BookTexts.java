package com.chaekcheck.foryou.internal.store;

import java.sql.Array;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/** Reads what the crawl PC computed: neighbours, tab blurbs and cover file keys (cycle 23). */
@Component
public class BookTexts {

	private final JdbcClient jdbc;

	public BookTexts(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The neighbour ids of each given book that has a row, nearest first. */
	public Map<Long, int[]> neighbours(Collection<Long> bookIds) {
		Map<Long, int[]> found = new HashMap<>();
		Long[] ids = distinct(bookIds);
		if (ids.length == 0) {
			return found;
		}
		jdbc.sql("SELECT book_id, neighbour_ids FROM foryou.book_neighbour WHERE book_id = ANY(CAST(:ids AS bigint[]))")
			.param("ids", ids)
			.query(rs -> {
				Array array = rs.getArray("neighbour_ids");
				Object[] values = (Object[]) array.getArray();
				int[] neighbours = new int[values.length];
				for (int i = 0; i < values.length; i++) {
					neighbours[i] = ((Number) values[i]).intValue();
				}
				found.put(rs.getLong("book_id"), neighbours);
			});
		return found;
	}

	public Map<Long, String> blurbs(Collection<Long> bookIds) {
		return texts("SELECT book_id, blurb AS text FROM foryou.book_blurb WHERE book_id = ANY(CAST(:ids AS bigint[]))",
				bookIds);
	}

	public Map<Long, String> coverKeys(Collection<Long> bookIds) {
		return texts("SELECT book_id, file_key AS text FROM foryou.book_cover WHERE book_id = ANY(CAST(:ids AS bigint[]))",
				bookIds);
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

	private static Long[] distinct(Collection<Long> bookIds) {
		Set<Long> ids = new LinkedHashSet<>(bookIds);
		ids.remove(null);
		return ids.toArray(Long[]::new);
	}

}
