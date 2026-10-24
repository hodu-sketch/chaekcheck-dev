package com.chaekcheck.usagelog;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Emptying and counting the usage log tables in tests (the writes commit on their own, so no rollback helps). */
public final class UsageLogTestTables {

	public static final List<String> TABLES = List.of("search_event", "book_view", "search_more", "library_click",
			"screen_event", "library_response_daily", "rejected_event", "dropped_batch", "ai_search_event");

	private UsageLogTestTables() {
	}

	public static void empty(JdbcClient jdbc) {
		jdbc.sql("TRUNCATE " + String.join(", ", TABLES.stream().map(table -> "usagelog." + table).toList()))
			.update();
	}

	public static long count(JdbcClient jdbc, String table) {
		if (!TABLES.contains(table)) {
			throw new IllegalArgumentException(table);
		}
		return jdbc.sql("SELECT count(*) FROM usagelog." + table).query(Long.class).single();
	}

}
