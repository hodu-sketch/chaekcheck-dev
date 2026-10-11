package com.chaekcheck.popular;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;

import com.chaekcheck.popular.internal.PopularCategory;

/**
 * A stored month written straight into the tables (September 2026, stored 2026-10-03 05:01:32 KST), 17 lists of 50
 * books in {@link PopularCategory} order. Most books have no ISBN; "전체" ranks 1 to 5 are set by the caller's
 * {@link #book}. Inside the test transaction.
 */
public final class PopularTestData {

	private final JdbcClient jdbc;

	public PopularTestData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	public void month() {
		long run = jdbc.sql("""
				INSERT INTO popular.fetch_run (target_month, trigger, started_at, finished_at, status, request_count,
				  book_count)
				VALUES (DATE '2026-09-01', 'SCHEDULED', TIMESTAMPTZ '2026-10-03 05:00:00+09',
				  TIMESTAMPTZ '2026-10-03 05:01:32+09', 'SUCCEEDED', 34, 850) RETURNING id""")
			.query(Long.class)
			.single();
		jdbc.sql("""
				INSERT INTO popular.ranking_month VALUES (DATE '2026-09-01', :run, TIMESTAMPTZ '2026-10-03 05:01:32.456+09')""")
			.param("run", run)
			.update();
		List<PopularCategory> categories = List.of(PopularCategory.values());
		for (int i = 0; i < categories.size(); i++) {
			PopularCategory category = categories.get(i);
			jdbc.sql("INSERT INTO popular.ranking_category VALUES (DATE '2026-09-01', :code, :sales, :order)")
				.param("code", category.code())
				.param("sales", category.isAll() ? null : 1_000L - i)
				.param("order", i)
				.update();
		}
		jdbc.sql("""
				INSERT INTO popular.ranking_entry (target_month, category_code, rank, title)
				SELECT c.target_month, c.category_code, r, c.category_code || '-' || r
				FROM popular.ranking_category c, generate_series(1, 50) r
				WHERE c.target_month = DATE '2026-09-01'""").update();
	}

	/** Sets the ISBN and title of a "전체" book. */
	public void book(int rank, String isbn13, String title) {
		jdbc.sql("""
				UPDATE popular.ranking_entry SET isbn13 = :isbn, title = :title
				WHERE target_month = DATE '2026-09-01' AND category_code = 'ALL' AND rank = :rank""")
			.param("isbn", isbn13)
			.param("title", title)
			.param("rank", rank)
			.update();
	}

	/** Stores a cover for a "전체" book; returns its id. */
	public long cover(int rank, String path) {
		jdbc.sql("""
				UPDATE popular.ranking_entry SET cover_path = :path
				WHERE target_month = DATE '2026-09-01' AND category_code = 'ALL' AND rank = :rank""")
			.param("path", path)
			.param("rank", rank)
			.update();
		return jdbc.sql("""
				INSERT INTO popular.cover (source_path, content_type, image, byte_size, fetched_at)
				VALUES (:path, 'image/jpeg', :image, 4, now()) RETURNING id""")
			.param("path", path)
			.param("image", new byte[] { 1, 2, 3, 4 })
			.query(Long.class)
			.single();
	}

	/** One of our books (catalog) with that ISBN, held by {@code libraryIds}. */
	public void ourBook(long id, String isbn13, long... libraryIds) {
		StringBuilder ids = new StringBuilder("{");
		for (int i = 0; i < libraryIds.length; i++) {
			ids.append(i == 0 ? "" : ",").append(libraryIds[i]);
		}
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, isbn13, content_type, library_ids)
				VALUES (:id, '책', '책', '책', :isbn, 'EBOOK', CAST(:libraries AS bigint[]))""")
			.param("id", id)
			.param("isbn", isbn13)
			.param("libraries", ids.append("}").toString())
			.update();
	}

}
