package com.chaekcheck.picks;

import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Stored picks written straight into the tables, inside the test transaction: one SUCCEEDED run (finished
 * 2026-10-12 05:00:41 KST) and the picks the caller adds.
 */
public final class PicksTestData {

	private final JdbcClient jdbc;

	public PicksTestData(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** The SUCCEEDED run the "받은 날" is read from. */
	public void run() {
		jdbc.sql("""
				INSERT INTO picks.fetch_run (trigger, started_at, finished_at, status, changed, request_count,
				  received_count, kept_count)
				VALUES ('SCHEDULED', TIMESTAMPTZ '2026-10-12 05:00:00+09', TIMESTAMPTZ '2026-10-12 05:00:41.456+09',
				  'SUCCEEDED', true, 1, 1, 1)""").update();
	}

	/**
	 * One pick.
	 * @param recomMonth {@code YYYY-MM}
	 * @param registeredOn {@code YYYY-MM-DD} or null
	 */
	public void pick(String recomNo, String drCode, String drCodeName, String recomMonth, String registeredOn,
			String title, String author, String isbn13) {
		jdbc.sql("""
				INSERT INTO picks.pick (recom_no, dr_code, dr_code_name, recom_month, registered_on, title, author,
				  isbn13)
				VALUES (:no, :code, :name, CAST(:month AS date), CAST(:registered AS date), :title, :author, :isbn)""")
			.param("no", recomNo)
			.param("code", drCode)
			.param("name", drCodeName)
			.param("month", recomMonth + "-01")
			.param("registered", registeredOn)
			.param("title", title)
			.param("author", author)
			.param("isbn", isbn13)
			.update();
	}

	/** Stores a cover for a pick; returns its id. */
	public long cover(String recomNo, String sourceUrl) {
		jdbc.sql("UPDATE picks.pick SET cover_source = :url WHERE recom_no = :no")
			.param("url", sourceUrl)
			.param("no", recomNo)
			.update();
		return jdbc.sql("""
				INSERT INTO picks.cover (source_url, content_type, image, byte_size, fetched_at)
				VALUES (:url, 'image/jpeg', :image, 4, now()) RETURNING id""")
			.param("url", sourceUrl)
			.param("image", new byte[] { 1, 2, 3, 4 })
			.query(Long.class)
			.single();
	}

	/** One of our books (catalog), held by {@code libraryIds}; {@code isbn13} may be null. */
	public void ourBook(long id, String isbn13, String title, String author, long... libraryIds) {
		StringBuilder ids = new StringBuilder("{");
		for (int i = 0; i < libraryIds.length; i++) {
			ids.append(i == 0 ? "" : ",").append(libraryIds[i]);
		}
		jdbc.sql("""
				INSERT INTO catalog.book (id, title, title_norm, main_title_norm, isbn13, content_type, library_ids)
				VALUES (:id, :title, 'x', 'x', :isbn, 'EBOOK', CAST(:libraries AS bigint[]))""")
			.param("id", id)
			.param("title", title)
			.param("isbn", isbn13)
			.param("libraries", ids.append("}").toString())
			.update();
		if (author != null) {
			jdbc.sql("""
					INSERT INTO catalog.book_contributor (book_id, name, name_norm, name_exact, role, position)
					VALUES (:id, :name, 'x', :exact, 'AUTHOR', 0)""")
				.param("id", id)
				.param("name", author)
				.param("exact", author.replace(" ", "").replace(".", "").toLowerCase(java.util.Locale.ROOT))
				.update();
		}
	}

}
