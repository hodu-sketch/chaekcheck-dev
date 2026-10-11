package com.chaekcheck.catalog.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.CatalogHoldings;
import com.chaekcheck.catalog.HoldingRef;

@Component
class JdbcCatalogHoldings implements CatalogHoldings {

	private static final String COLUMNS = """
			SELECT id, book_id, library_id, platform, detail_url, title_raw, author_raw, publisher_raw
			FROM catalog.holding""";

	private static final RowMapper<HoldingRef> ROW = (rs, rowNum) -> new HoldingRef(rs.getLong("id"),
			rs.getLong("book_id"), rs.getLong("library_id"), rs.getString("platform"), rs.getString("detail_url"),
			rs.getString("title_raw"), rs.getString("author_raw"), rs.getString("publisher_raw"));

	private final JdbcClient jdbc;

	JdbcCatalogHoldings(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public List<HoldingRef> holdingsOf(Collection<Long> bookIds, Collection<Long> libraryIds) {
		if (bookIds.isEmpty() || libraryIds.isEmpty()) {
			return List.of();
		}
		// holding_book_id_library_id serves both conditions: only the plan libraries' rows are read (ADR-0066)
		List<HoldingRef> found = jdbc.sql(COLUMNS + """
				 WHERE book_id = ANY(CAST(:ids AS bigint[])) AND library_id = ANY(CAST(:libraries AS bigint[]))
				ORDER BY book_id, library_id, id""")
			.param("ids", bookIds.toArray(Long[]::new))
			.param("libraries", libraryIds.toArray(Long[]::new))
			.query(ROW)
			.list();
		Map<Long, List<HoldingRef>> byBook = new HashMap<>();
		found.forEach(ref -> byBook.computeIfAbsent(ref.bookId(), id -> new ArrayList<>()).add(ref));
		List<HoldingRef> ordered = new ArrayList<>(found.size());
		bookIds.stream().distinct().forEach(id -> ordered.addAll(byBook.getOrDefault(id, List.of())));
		return List.copyOf(ordered);
	}

	@Override
	public List<HoldingRef> holdings(Collection<Long> holdingIds) {
		if (holdingIds.isEmpty()) {
			return List.of();
		}
		Map<Long, HoldingRef> byId = new HashMap<>();
		jdbc.sql(COLUMNS + " WHERE id = ANY(CAST(:ids AS bigint[]))")
			.param("ids", holdingIds.toArray(Long[]::new))
			.query(ROW)
			.list()
			.forEach(ref -> byId.put(ref.holdingId(), ref));
		return holdingIds.stream().distinct().map(byId::get).filter(ref -> ref != null).toList();
	}

}
