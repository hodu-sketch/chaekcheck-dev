package com.chaekcheck.collection.internal.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.function.Consumer;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;

/**
 * The national bibliography records kept as read from the TTL files (plan ADR-0069, cycle 08 handoff step 3):
 * {@code collection.national_bibliography_record} and the one-row {@code national_bibliography_source} with the
 * fingerprint of the files. Only records with an ISBN and a title are kept (the others can never give an ISBN).
 * A record with several ISBNs is kept as one row per ISBN with the same text: its key then has two ISBNs and stays
 * ambiguous, as when the record was read from the file.
 */
class NationalBibliographyStore {

	private static final int FLUSH_CHARS = 1 << 20;

	private final DataSource dataSource;

	NationalBibliographyStore(DataSource dataSource) {
		this.dataSource = dataSource;
	}

	/** A record as read from the files, before any normalization. */
	record Raw(String isbn13, String title, String creator, String publisher) {
	}

	/** Receives the records while the files are read. */
	interface Writer {

		void add(Raw raw);

	}

	/** Reads the files and hands every record to the writer; the records are stored as they come. */
	@FunctionalInterface
	interface FileReader {

		void read(Writer writer) throws IOException;

	}

	/** @return the fingerprint of the files the stored records come from, or null when none is stored */
	String fingerprint() throws SQLException {
		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery(
						"SELECT source_fingerprint FROM collection.national_bibliography_source")) {
			return rs.next() ? rs.getString(1) : null;
		}
	}

	/** Every stored record, in the order the files gave them. */
	void forEach(Consumer<Raw> action) throws SQLException {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			// a cursor (fetch size) needs a transaction; without one the driver reads every row into memory first
			connection.setAutoCommit(false);
			try (Statement statement = connection.createStatement()) {
				statement.setFetchSize(10_000);
				try (ResultSet rs = statement.executeQuery("""
						SELECT isbn13, title_raw, author_raw, publisher_raw
						FROM collection.national_bibliography_record ORDER BY id""")) {
					while (rs.next()) {
						action.accept(new Raw(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4)));
					}
				}
				connection.commit();
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
		}
	}

	/**
	 * Rewrites the records from the files [확정 순서]: the fingerprint row is deleted and committed first, then the
	 * records are replaced, and the fingerprint is written last. A rewrite that stops leaves no fingerprint, so the
	 * next import reads the files again.
	 */
	void rewrite(String fingerprint, Instant builtAt, FileReader files) throws SQLException, IOException {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(true);
			try (Statement statement = connection.createStatement()) {
				statement.execute("DELETE FROM collection.national_bibliography_source");
			}
			connection.setAutoCommit(false);
			try {
				try (Statement statement = connection.createStatement()) {
					statement.execute("TRUNCATE collection.national_bibliography_record");
				}
				CopyIn copy = connection.unwrap(PGConnection.class).getCopyAPI().copyIn("""
						COPY collection.national_bibliography_record (id, isbn13, title_raw, author_raw, publisher_raw)
						FROM STDIN""");
				CopyWriter writer = new CopyWriter(copy);
				try {
					files.read(writer);
					writer.close();
				}
				finally {
					if (copy.isActive()) {
						copy.cancelCopy();
					}
				}
				try (PreparedStatement statement = connection.prepareStatement("""
						INSERT INTO collection.national_bibliography_source (source_fingerprint, built_at)
						VALUES (?, ?)""")) {
					statement.setString(1, fingerprint);
					statement.setTimestamp(2, Timestamp.from(builtAt));
					statement.executeUpdate();
				}
				connection.commit();
			}
			catch (SQLException | IOException | RuntimeException ex) {
				connection.rollback();
				throw ex;
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
		}
	}

	/** COPY text format: tab separated, {@code \N} for null, backslash escapes. */
	private static final class CopyWriter implements Writer {

		private final CopyIn copy;

		private final StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 4096);

		private long id;

		CopyWriter(CopyIn copy) {
			this.copy = copy;
		}

		@Override
		public void add(Raw raw) {
			field(String.valueOf(++id)).append('\t');
			field(raw.isbn13()).append('\t');
			field(raw.title()).append('\t');
			field(raw.creator()).append('\t');
			field(raw.publisher()).append('\n');
			if (buffer.length() >= FLUSH_CHARS) {
				flush();
			}
		}

		private StringBuilder field(String value) {
			if (value == null) {
				return buffer.append("\\N");
			}
			for (int i = 0; i < value.length(); i++) {
				char c = value.charAt(i);
				switch (c) {
					case '\\' -> buffer.append("\\\\");
					case '\t' -> buffer.append("\\t");
					case '\n' -> buffer.append("\\n");
					case '\r' -> buffer.append("\\r");
					default -> buffer.append(c);
				}
			}
			return buffer;
		}

		private void flush() {
			byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
			try {
				copy.writeToCopy(bytes, 0, bytes.length);
			}
			catch (SQLException ex) {
				throw new IllegalStateException(ex);
			}
			buffer.setLength(0);
		}

		void close() throws SQLException {
			flush();
			copy.endCopy();
		}

	}

}
