package com.chaekcheck.catalog.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.normalize.Contributors.Contributor;

/**
 * {@link CatalogLoad} with PostgreSQL COPY (moved from the catalog importer's writer, ADR-0060). Only one COPY can
 * run on a connection, so the contributors of the books are kept in a temporary file in COPY text format and copied
 * when the first holding arrives. Holding ids are new on every load; nothing on the server keeps them (ADR-0014).
 * <p>
 * The load goes into temporary tables first ({@code load_*}, dropped at commit). The book ids are then decided in
 * SQL against the previous holdings, which are still in {@code catalog.holding} (5.2: matched on the unique
 * {@code (library_id, item_key)}), and only then are the catalog tables emptied and filled from the temporary ones.
 * Doing it in the database keeps the 20 million holdings out of the importer's memory (2026-10-05).
 */
@Component
class JdbcCatalogLoad implements CatalogLoad {

	private static final Logger log = LoggerFactory.getLogger(JdbcCatalogLoad.class);

	private static final int FLUSH_CHARS = 1 << 20;

	private final DataSource dataSource;

	JdbcCatalogLoad(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	@Override
	public Result replaceAll(String source, Loader loader) {
		Result result;
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			// before the session: it opens the book COPY, and no other statement can run during a COPY.
			// LIKE copies the columns, NOT NULL and defaults; the catalog tables check the rest when the rows move there.
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						CREATE TEMPORARY TABLE load_book (LIKE catalog.book INCLUDING DEFAULTS) ON COMMIT DROP;
						CREATE TEMPORARY TABLE load_book_contributor (LIKE catalog.book_contributor INCLUDING DEFAULTS) ON COMMIT DROP;
						CREATE TEMPORARY TABLE load_holding (LIKE catalog.holding INCLUDING DEFAULTS) ON COMMIT DROP""");
			}
			try (LoadSession session = new LoadSession(connection)) {
				loader.load(session);
				result = session.finish();
				connection.commit();
			}
			catch (Exception ex) {
				connection.rollback();
				throw ex instanceof RuntimeException runtime ? runtime
						: new IllegalStateException("Catalog load from " + source + " failed", ex);
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
			// VACUUM cannot run inside a transaction: after the commit, in autocommit (ADR-0054)
			connection.setAutoCommit(true);
			try (Statement statement = connection.createStatement()) {
				statement.execute("VACUUM (ANALYZE) catalog.book, catalog.book_contributor, catalog.holding,"
						+ " catalog.catalog_library_snapshot");
			}
			connection.setAutoCommit(autoCommit);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Catalog load from " + source + " failed", ex);
		}
		log.info("catalog load from {}: {}", source, result);
		return result;
	}

	private static final class LoadSession implements Session, AutoCloseable {

		private final Connection connection;

		private final CopyManager copy;

		private final Path contributorFile;

		private final BufferedWriter contributors;

		private final List<Object[]> snapshots = new ArrayList<>();

		private Copy books;

		private Copy holdings;

		private long bookCount;

		private long contributorCount;

		private long holdingCount;

		LoadSession(Connection connection) throws SQLException, IOException {
			this.connection = connection;
			this.copy = connection.unwrap(PGConnection.class).getCopyAPI();
			this.contributorFile = Files.createTempFile("catalog-contributors", ".copy");
			this.contributors = Files.newBufferedWriter(contributorFile, StandardCharsets.UTF_8);
			this.books = new Copy(copy, """
					COPY load_book (id, title, author_display, publisher, title_norm, main_title_norm,
					  publisher_norm, published_on, isbn13, content_type, library_ids) FROM STDIN""");
		}

		@Override
		public long addBook(NewBook book) {
			if (books == null) {
				throw new IllegalStateException("Books must come before the first holding");
			}
			long id = ++bookCount;
			try {
				books.row(id, book.title(), book.authorDisplay(), book.publisher(), book.titleNorm(),
						book.mainTitleNorm(), book.publisherNorm(), book.publishedOn(), book.isbn13(),
						book.contentType(), arrayLiteral(book.libraryIds()));
				for (Contributor c : book.contributors()) {
					Copy.appendRow(contributors, ++contributorCount, id, c.name(), c.alias(), c.nameNorm(),
							c.nameExact(), c.role().name(), c.position());
				}
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
			return id;
		}

		@Override
		public void addHolding(NewHolding h) {
			try {
				if (holdings == null) {
					endBooks();
					holdings = new Copy(copy, """
							COPY load_holding (id, book_id, library_id, platform, item_key, detail_url, content_type,
							  title_raw, author_raw, publisher_raw, published_raw, supplier, alt_code, first_seen_at)
							FROM STDIN""");
				}
				holdings.row(++holdingCount, h.bookId(), h.libraryId(), h.platform(), h.itemKey(), h.detailUrl(),
						h.contentType(), h.titleRaw(), h.authorRaw(), h.publisherRaw(), h.publishedRaw(), h.supplier(),
						h.altCode(), h.firstSeenAt());
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
			catch (IOException ex) {
				throw new UncheckedIOException(ex);
			}
		}

		@Override
		public void addLibrarySnapshot(long libraryId, Instant collectedAt) {
			snapshots.add(new Object[] { libraryId, collectedAt });
		}

		Result finish() throws SQLException, IOException {
			if (holdings == null) {
				endBooks();
			}
			else {
				holdings.close();
				holdings = null;
			}
			BookIds bookIds = BookIds.decide(connection);
			try (Statement statement = connection.createStatement()) {
				statement.execute("""
						TRUNCATE catalog.book_contributor, catalog.holding, catalog.book,
						  catalog.catalog_library_snapshot""");
				statement.execute("""
						INSERT INTO catalog.book (id, title, author_display, publisher, title_norm, main_title_norm,
						  publisher_norm, published_on, isbn13, content_type, library_ids)
						SELECT m.book_id, b.title, b.author_display, b.publisher, b.title_norm, b.main_title_norm,
						  b.publisher_norm, b.published_on, b.isbn13, b.content_type, b.library_ids
						FROM load_book b JOIN load_book_id m ON m.load_id = b.id""");
				statement.execute("""
						INSERT INTO catalog.book_contributor (id, book_id, name, alias, name_norm, name_exact, role,
						  position)
						SELECT c.id, m.book_id, c.name, c.alias, c.name_norm, c.name_exact, c.role, c.position
						FROM load_book_contributor c JOIN load_book_id m ON m.load_id = c.book_id""");
				statement.execute("""
						INSERT INTO catalog.holding (id, book_id, library_id, platform, item_key, detail_url,
						  content_type, title_raw, author_raw, publisher_raw, published_raw, supplier, alt_code,
						  first_seen_at)
						SELECT h.id, m.book_id, h.library_id, h.platform, h.item_key, h.detail_url, h.content_type,
						  h.title_raw, h.author_raw, h.publisher_raw, h.published_raw, h.supplier, h.alt_code,
						  h.first_seen_at
						FROM load_holding h JOIN load_book_id m ON m.load_id = h.book_id""");
			}
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO catalog.catalog_library_snapshot (library_id, collected_at) VALUES (?, ?)""")) {
				for (Object[] snapshot : snapshots) {
					statement.setLong(1, (Long) snapshot[0]);
					statement.setTimestamp(2, Timestamp.from((Instant) snapshot[1]));
					statement.addBatch();
				}
				statement.executeBatch();
			}
			try (Statement statement = connection.createStatement()) {
				// not the book ids: their sequence never goes back (BookIds)
				for (String table : List.of("book_contributor", "holding")) {
					statement.execute("SELECT setval(pg_get_serial_sequence('catalog." + table
							+ "', 'id'), coalesce((SELECT max(id) FROM catalog." + table + "), 0) + 1, false)");
				}
			}
			return new Result(bookCount, contributorCount, holdingCount, snapshots.size(), bookIds.kept(),
					bookIds.added(), bookIds.dropped());
		}

		/** Ends the book COPY and copies the contributors kept in the temporary file. */
		private void endBooks() throws SQLException, IOException {
			if (books == null) {
				return;
			}
			books.close();
			books = null;
			contributors.close();
			try (InputStream in = Files.newInputStream(contributorFile)) {
				copy.copyIn("""
						COPY load_book_contributor (id, book_id, name, alias, name_norm, name_exact, role, position)
						FROM STDIN""", in, 1 << 16);
			}
		}

		/** Cancels a COPY left open by a failed loader, so the transaction can roll back. */
		@Override
		public void close() throws IOException, SQLException {
			for (Copy open : new Copy[] { books, holdings }) {
				if (open != null) {
					open.cancel();
				}
			}
			contributors.close();
			Files.deleteIfExists(contributorFile);
		}

		private static String arrayLiteral(long[] ids) {
			StringBuilder text = new StringBuilder(ids.length * 5 + 2).append('{');
			for (int i = 0; i < ids.length; i++) {
				if (i > 0) {
					text.append(',');
				}
				text.append(ids[i]);
			}
			return text.append('}').toString();
		}

	}

	/**
	 * Decides the stored id of every book in {@code load_book} (plan {@code catalog-refresh.md} 5.2) and writes the
	 * pairs to {@code load_book_id (load_id, book_id)}. Runs before the catalog tables are emptied: the previous
	 * holdings are the other side of the match.
	 */
	record BookIds(long kept, long added, long dropped) {

		static BookIds decide(Connection connection) throws SQLException {
			try (Statement statement = connection.createStatement()) {
				// temporary tables are never analyzed on their own; the join below needs the row counts
				statement.execute("ANALYZE load_book, load_holding");
				// 1. each old book goes to the new book sharing most of its holdings (a split keeps the id in one
				// place; ties go to the smaller load id). 2. a new book chosen by several old books (a merge) keeps
				// the smallest old id; the other old ids are dropped.
				statement.execute("""
						CREATE TEMPORARY TABLE load_book_id ON COMMIT DROP AS
						WITH shared AS (
						  SELECT n.book_id AS load_id, o.book_id AS old_id, count(*) AS holdings
						  FROM load_holding n
						  JOIN catalog.holding o ON o.library_id = n.library_id AND o.item_key = n.item_key
						  GROUP BY n.book_id, o.book_id
						), best AS (
						  SELECT DISTINCT ON (old_id) old_id, load_id FROM shared ORDER BY old_id, holdings DESC, load_id
						)
						SELECT load_id, min(old_id) AS book_id FROM best GROUP BY load_id""");
				long kept = count(statement, "SELECT count(*) FROM load_book_id");
				long oldBooks = count(statement, "SELECT count(*) FROM catalog.book");
				// never reuse an id: new ones start above the highest id the sequence or the table ever had
				String sequence = string(statement, "SELECT pg_get_serial_sequence('catalog.book', 'id')");
				long highest = count(statement, "SELECT greatest(coalesce((SELECT max(id) FROM catalog.book), 0),"
						+ " (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM " + sequence + "))");
				statement.execute(highest > 0 ? "SELECT setval('" + sequence + "', " + highest + ", true)"
						: "SELECT setval('" + sequence + "', 1, false)");
				long added = statement.executeUpdate("""
						INSERT INTO load_book_id (load_id, book_id)
						SELECT n.id, nextval('%s') FROM (
						  SELECT b.id FROM load_book b
						  WHERE NOT EXISTS (SELECT 1 FROM load_book_id m WHERE m.load_id = b.id)
						  ORDER BY b.id
						) n""".formatted(sequence));
				statement.execute("ANALYZE load_book_id");
				return new BookIds(kept, added, oldBooks - kept);
			}
		}

		private static long count(Statement statement, String sql) throws SQLException {
			try (ResultSet rs = statement.executeQuery(sql)) {
				rs.next();
				return rs.getLong(1);
			}
		}

		private static String string(Statement statement, String sql) throws SQLException {
			try (ResultSet rs = statement.executeQuery(sql)) {
				rs.next();
				return rs.getString(1);
			}
		}

	}

	/** COPY text format writer: tab separated, {@code \N} for null, backslash escapes. */
	private static final class Copy {

		private final CopyIn in;

		private final StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 4096);

		Copy(CopyManager copy, String sql) throws SQLException {
			this.in = copy.copyIn(sql);
		}

		void row(Object... values) throws SQLException {
			append(buffer, values);
			if (buffer.length() >= FLUSH_CHARS) {
				flush();
			}
		}

		static void appendRow(Appendable out, Object... values) throws IOException {
			StringBuilder line = new StringBuilder(128);
			append(line, values);
			out.append(line);
		}

		private static void append(StringBuilder buffer, Object... values) {
			for (int i = 0; i < values.length; i++) {
				if (i > 0) {
					buffer.append('\t');
				}
				Object value = values[i];
				if (value == null) {
					buffer.append("\\N");
					continue;
				}
				String text = value.toString();
				for (int k = 0; k < text.length(); k++) {
					char c = text.charAt(k);
					switch (c) {
						case '\\' -> buffer.append("\\\\");
						case '\t' -> buffer.append("\\t");
						case '\n' -> buffer.append("\\n");
						case '\r' -> buffer.append("\\r");
						default -> buffer.append(c);
					}
				}
			}
			buffer.append('\n');
		}

		private void flush() throws SQLException {
			byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
			in.writeToCopy(bytes, 0, bytes.length);
			buffer.setLength(0);
		}

		void close() throws SQLException {
			flush();
			in.endCopy();
		}

		void cancel() throws SQLException {
			if (in.isActive()) {
				in.cancelCopy();
			}
		}

	}

}
