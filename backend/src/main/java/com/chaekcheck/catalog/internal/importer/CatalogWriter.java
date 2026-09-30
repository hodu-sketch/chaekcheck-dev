package com.chaekcheck.catalog.internal.importer;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import com.chaekcheck.catalog.internal.importer.BookGrouping.Book;
import com.chaekcheck.catalog.internal.normalize.Contributors.Contributor;

/**
 * Replaces the catalog data in one transaction with PostgreSQL COPY (handoff 5단계 "쓰기"). A new import deletes the
 * old data first [안], so holding ids change; nothing on the server keeps them (ADR-0014).
 */
final class CatalogWriter {

	private static final int FLUSH_CHARS = 1 << 20;

	private CatalogWriter() {
	}

	/** @return the {@code catalog_import} id */
	static long write(Connection connection, String source, Instant importedAt, List<CollectedCatalog.Library> libraries,
			List<CollectedRow> rows, List<Book> books, ImportReport report) throws SQLException {
		boolean autoCommit = connection.getAutoCommit();
		connection.setAutoCommit(false);
		try {
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						TRUNCATE catalog.book_contributor, catalog.holding, catalog.book, catalog.catalog_library_snapshot,
						  catalog.catalog_import""");
			}
			CopyManager copy = connection.unwrap(PGConnection.class).getCopyAPI();
			copyBooks(copy, books);
			report.books = books.size();
			report.contributors = copyContributors(copy, books);
			report.holdings = copyHoldings(copy, rows, books);
			long importId = insertImport(connection, source, importedAt, report);
			insertSnapshots(connection, libraries, importId);
			try (Statement statement = connection.createStatement()) {
				for (String table : List.of("book", "book_contributor", "holding")) {
					statement.execute("SELECT setval(pg_get_serial_sequence('catalog." + table
							+ "', 'id'), coalesce((SELECT max(id) FROM catalog." + table + "), 0) + 1, false)");
				}
			}
			connection.commit();
			try (Statement statement = connection.createStatement()) {
				statement.execute("ANALYZE catalog.book, catalog.book_contributor, catalog.holding");
			}
			return importId;
		}
		catch (SQLException | RuntimeException ex) {
			connection.rollback();
			throw ex;
		}
		finally {
			connection.setAutoCommit(autoCommit);
		}
	}

	private static void copyBooks(CopyManager copy, List<Book> books) throws SQLException {
		try (Copy out = new Copy(copy, """
				COPY catalog.book (id, title, author_display, publisher, title_norm, main_title_norm, publisher_norm,
				  published_on, isbn13, content_type) FROM STDIN""")) {
			for (int i = 0; i < books.size(); i++) {
				Book b = books.get(i);
				out.row(i + 1, b.title(), b.authorDisplay(), b.publisher(), b.titleNorm(), b.mainTitleNorm(),
						b.publisherNorm(), b.publishedOn(), b.isbn13(), b.contentType());
			}
		}
	}

	private static long copyContributors(CopyManager copy, List<Book> books) throws SQLException {
		long id = 0;
		try (Copy out = new Copy(copy, """
				COPY catalog.book_contributor (id, book_id, name, alias, name_norm, name_exact, role, position)
				FROM STDIN""")) {
			for (int i = 0; i < books.size(); i++) {
				for (Contributor c : books.get(i).contributors()) {
					out.row(++id, i + 1, c.name(), c.alias(), c.nameNorm(), c.nameExact(), c.role().name(),
							c.position());
				}
			}
		}
		return id;
	}

	private static long copyHoldings(CopyManager copy, List<CollectedRow> rows, List<Book> books) throws SQLException {
		long id = 0;
		try (Copy out = new Copy(copy, """
				COPY catalog.holding (id, book_id, library_id, platform, item_key, detail_url, content_type, title_raw,
				  author_raw, publisher_raw, published_raw, supplier, alt_code, first_seen_at) FROM STDIN""")) {
			for (int i = 0; i < books.size(); i++) {
				for (int r : books.get(i).rows()) {
					CollectedRow row = rows.get(r);
					out.row(++id, i + 1, row.libraryId(), row.platformType(), row.itemKey(), row.detailUrl(),
							row.contentType(), row.title(), row.authorRaw(), row.publisher(), row.publishedRaw(),
							row.supplier(), row.altCode(), row.firstSeenAt());
				}
			}
		}
		return id;
	}

	private static long insertImport(Connection connection, String source, Instant importedAt, ImportReport report)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog.catalog_import (source, imported_at, book_count, holding_count, skipped_count)
				VALUES (?, ?, ?, ?, ?) RETURNING id""")) {
			statement.setString(1, source);
			statement.setTimestamp(2, Timestamp.from(importedAt));
			statement.setLong(3, report.books);
			statement.setLong(4, report.holdings);
			statement.setLong(5, report.skippedCount());
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private static void insertSnapshots(Connection connection, List<CollectedCatalog.Library> libraries, long importId)
			throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog.catalog_library_snapshot (library_id, collected_at, import_id) VALUES (?, ?, ?)""")) {
			for (CollectedCatalog.Library library : libraries) {
				statement.setLong(1, library.id());
				statement.setTimestamp(2, Timestamp.from(library.collectedAt()));
				statement.setLong(3, importId);
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	/** COPY text format writer: tab separated, {@code \N} for null, backslash escapes. */
	private static final class Copy implements AutoCloseable {

		private final CopyIn in;

		private final StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 4096);

		Copy(CopyManager copy, String sql) throws SQLException {
			this.in = copy.copyIn(sql);
		}

		void row(Object... values) throws SQLException {
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					buffer.append('\t');
				}
				append(values[i]);
			}
			buffer.append('\n');
			if (buffer.length() >= FLUSH_CHARS) {
				flush();
			}
		}

		private void append(Object value) {
			if (value == null) {
				buffer.append("\\N");
				return;
			}
			String text = value.toString();
			for (int i = 0; i < text.length(); i++) {
				char c = text.charAt(i);
				switch (c) {
					case '\\' -> buffer.append("\\\\");
					case '\t' -> buffer.append("\\t");
					case '\n' -> buffer.append("\\n");
					case '\r' -> buffer.append("\\r");
					default -> buffer.append(c);
				}
			}
		}

		private void flush() throws SQLException {
			byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
			in.writeToCopy(bytes, 0, bytes.length);
			buffer.setLength(0);
		}

		@Override
		public void close() throws SQLException {
			flush();
			in.endCopy();
		}

	}

}
