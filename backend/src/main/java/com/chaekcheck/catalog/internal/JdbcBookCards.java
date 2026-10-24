package com.chaekcheck.catalog.internal;

import java.sql.Array;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookCards;

/** Reads {@code catalog.book}, {@code book_contributor} and {@code holding} for the for-you cards (cycle 23). */
@Component
class JdbcBookCards implements BookCards {

	private final JdbcClient jdbc;

	JdbcBookCards(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<BookCard> cards(Collection<Long> bookIds) {
		Long[] ids = distinct(bookIds);
		if (ids.length == 0) {
			return List.of();
		}
		// the first AUTHOR or UNKNOWN contributor by position, like Contributors.firstNameExact for the lists
		return jdbc.sql("""
				SELECT b.id, b.title, b.author_display, b.publisher, extract(year FROM b.published_on)::int AS year,
				  b.library_ids,
				  (SELECT c.name_exact FROM catalog.book_contributor c
				   WHERE c.book_id = b.id AND c.role IN ('AUTHOR', 'UNKNOWN') ORDER BY c.position, c.id LIMIT 1) AS author_key
				FROM catalog.book b WHERE b.id = ANY(CAST(:ids AS bigint[]))""")
			.param("ids", ids)
			.query((rs, rowNum) -> new BookCard(rs.getLong("id"), rs.getString("title"), rs.getString("author_display"),
					rs.getString("publisher"), (Integer) rs.getObject("year"), rs.getString("author_key"),
					longs(rs.getArray("library_ids"))))
			.list();
	}

	@Override
	public Map<HoldingKey, Long> byHoldingKeys(Collection<HoldingKey> keys) {
		Set<HoldingKey> wanted = new LinkedHashSet<>();
		for (HoldingKey key : keys) {
			if (key != null && key.itemKey() != null) {
				wanted.add(key);
			}
		}
		if (wanted.isEmpty()) {
			return Map.of();
		}
		List<Long> libraries = new ArrayList<>();
		List<String> items = new ArrayList<>();
		for (HoldingKey key : wanted) {
			libraries.add(key.libraryId());
			items.add(key.itemKey());
		}
		Map<HoldingKey, Long> found = new HashMap<>();
		// one probe of holding_library_item_key per key
		jdbc.sql("""
				SELECT h.library_id, h.item_key, h.book_id
				FROM unnest(CAST(:libraries AS bigint[]), CAST(:items AS text[])) AS k (library_id, item_key)
				JOIN catalog.holding h ON h.library_id = k.library_id AND h.item_key = k.item_key""")
			.param("libraries", libraries.toArray(Long[]::new))
			.param("items", items.toArray(String[]::new))
			.query(rs -> {
				found.put(new HoldingKey(rs.getLong("library_id"), rs.getString("item_key")), rs.getLong("book_id"));
			});
		return found;
	}

	@Override
	public Map<Long, Long> workKeys(Collection<Long> bookIds) {
		Long[] ids = distinct(bookIds);
		if (ids.length == 0) {
			return Map.of();
		}
		Map<Long, Edition> editions = new HashMap<>();
		jdbc.sql("""
				SELECT b.id, b.main_title_norm, cardinality(b.library_ids) AS held,
				  array(SELECT DISTINCT c.name_exact FROM catalog.book_contributor c
				        WHERE c.book_id = b.id AND c.role IN ('AUTHOR', 'UNKNOWN')) AS names
				FROM catalog.book b WHERE b.id = ANY(CAST(:ids AS bigint[]))""")
			.param("ids", ids)
			.query(rs -> {
				long id = rs.getLong("id");
				editions.put(id, new Edition(id, rs.getString("main_title_norm"), rs.getInt("held"),
						strings(rs.getArray("names"))));
			});
		Groups groups = new Groups(editions.keySet());
		// editions join when the main title is the same and a name is shared; a name chain joins them all
		Map<String, Long> firstWithTitleAndName = new HashMap<>();
		for (Edition edition : editions.values()) {
			for (String name : edition.names()) {
				Long other = firstWithTitleAndName.putIfAbsent(edition.mainTitle() + '\u0000' + name, edition.id());
				if (other != null) {
					groups.join(edition.id(), other);
				}
			}
		}
		Map<Long, Edition> representative = new HashMap<>();
		for (Edition edition : editions.values()) {
			representative.merge(groups.root(edition.id()), edition, JdbcBookCards::better);
		}
		Map<Long, Long> keys = new HashMap<>();
		for (Edition edition : editions.values()) {
			keys.put(edition.id(), representative.get(groups.root(edition.id())).id());
		}
		return keys;
	}

	/** The edition held by more libraries, then the smaller id. */
	private static Edition better(Edition a, Edition b) {
		if (a.held() != b.held()) {
			return a.held() > b.held() ? a : b;
		}
		return a.id() < b.id() ? a : b;
	}

	private record Edition(long id, String mainTitle, int held, List<String> names) {
	}

	/** Union-find over book ids. */
	private static final class Groups {

		private final Map<Long, Long> parent = new HashMap<>();

		Groups(Set<Long> ids) {
			ids.forEach(id -> parent.put(id, id));
		}

		long root(long id) {
			long root = id;
			while (parent.get(root) != root) {
				root = parent.get(root);
			}
			long step = id;
			while (step != root) {
				long next = parent.get(step);
				parent.put(step, root);
				step = next;
			}
			return root;
		}

		void join(long a, long b) {
			long rootA = root(a);
			long rootB = root(b);
			if (rootA != rootB) {
				parent.put(Math.max(rootA, rootB), Math.min(rootA, rootB));
			}
		}

	}

	private static Long[] distinct(Collection<Long> bookIds) {
		Set<Long> ids = new LinkedHashSet<>();
		for (Long id : bookIds) {
			if (id != null) {
				ids.add(id);
			}
		}
		return ids.toArray(Long[]::new);
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

	private static List<String> strings(Array array) throws SQLException {
		if (array == null) {
			return List.of();
		}
		List<String> values = new ArrayList<>();
		for (Object value : (Object[]) array.getArray()) {
			values.add((String) value);
		}
		return values;
	}

}
