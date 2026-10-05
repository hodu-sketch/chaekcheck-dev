package com.chaekcheck.collection.internal.importer;

import java.util.HashMap;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;

/** {@link CollectionLinks} from {@code collection.collection_link} (seed rows, library/008 and collection/002). */
final class JdbcCollectionLinks implements CollectionLinks {

	private final JdbcClient jdbc;

	JdbcCollectionLinks(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public Map<Long, Long> all() {
		Map<Long, Long> links = new HashMap<>();
		jdbc.sql("SELECT collection_library_id, library_id FROM collection.collection_link")
			.query(rs -> {
				links.put(rs.getLong(1), rs.getLong(2));
			});
		return links;
	}

}
