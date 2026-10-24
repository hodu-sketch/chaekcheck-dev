package com.chaekcheck.foryou.internal.store;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Iterator;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.foryou.ForYouTransfer;

/**
 * Neighbours by COPY into {@code foryou.book_neighbour_next} and a swap (design-delta "넣는 명령"); blurbs and covers by
 * upsert. The next table is made and dropped here, never by Liquibase.
 */
@Component
class JdbcForYouTransfer implements ForYouTransfer {

	private static final Logger log = LoggerFactory.getLogger(JdbcForYouTransfer.class);

	private static final int FLUSH_CHARS = 1 << 20;

	private final DataSource dataSource;

	private final JdbcClient jdbc;

	JdbcForYouTransfer(DataSource dataSource, JdbcClient jdbc) {
		this.dataSource = dataSource;
		this.jdbc = jdbc;
	}

	@Override
	public long replaceAllNeighbours(Iterator<Neighbours> rows, LocalDate computedOn) {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(true);
			try {
				execute(connection, "DROP TABLE IF EXISTS foryou.book_neighbour_next");
				execute(connection, "CREATE TABLE foryou.book_neighbour_next (LIKE foryou.book_neighbour INCLUDING ALL)");
				long count;
				try {
					count = copy(connection, "foryou.book_neighbour_next", rows, computedOn);
					connection.setAutoCommit(false);
					execute(connection, "LOCK TABLE foryou.book_neighbour IN ACCESS EXCLUSIVE MODE");
					execute(connection, "DROP TABLE foryou.book_neighbour");
					execute(connection, "ALTER TABLE foryou.book_neighbour_next RENAME TO book_neighbour");
					execute(connection, "ALTER INDEX foryou.book_neighbour_next_pkey RENAME TO book_neighbour_pkey");
					connection.commit();
				}
				catch (SQLException | RuntimeException ex) {
					if (!connection.getAutoCommit()) {
						connection.rollback();
						connection.setAutoCommit(true);
					}
					execute(connection, "DROP TABLE IF EXISTS foryou.book_neighbour_next");
					throw ex;
				}
				log.info("foryou neighbours swapped in: {} rows", count);
				return count;
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("neighbours full load failed, the table before stays: " + ex.getMessage(), ex);
		}
	}

	@Override
	public long upsertNeighbours(Iterator<Neighbours> rows, LocalDate computedOn) {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			connection.setAutoCommit(false);
			try {
				execute(connection, """
						CREATE TEMPORARY TABLE book_neighbour_add (LIKE foryou.book_neighbour INCLUDING DEFAULTS)
						ON COMMIT DROP""");
				long count = copy(connection, "book_neighbour_add", rows, computedOn);
				execute(connection, """
						INSERT INTO foryou.book_neighbour SELECT * FROM book_neighbour_add
						ON CONFLICT (book_id) DO UPDATE SET neighbour_ids = EXCLUDED.neighbour_ids,
						  computed_on = EXCLUDED.computed_on""");
				connection.commit();
				return count;
			}
			catch (SQLException | RuntimeException ex) {
				connection.rollback();
				throw ex;
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("neighbours add failed: " + ex.getMessage(), ex);
		}
	}

	@Override
	@Transactional
	public int upsertBlurbs(Collection<Blurb> blurbs) {
		for (Blurb blurb : blurbs) {
			jdbc.sql("""
					INSERT INTO foryou.book_blurb (book_id, blurb, made_on) VALUES (?, ?, ?)
					ON CONFLICT (book_id) DO UPDATE SET blurb = EXCLUDED.blurb, made_on = EXCLUDED.made_on""")
				.params(blurb.bookId(), blurb.blurb(), blurb.madeOn())
				.update();
		}
		return blurbs.size();
	}

	@Override
	@Transactional
	public int upsertCovers(Collection<Cover> covers) {
		for (Cover cover : covers) {
			jdbc.sql("""
					INSERT INTO foryou.book_cover (book_id, file_key) VALUES (?, ?)
					ON CONFLICT (book_id) DO UPDATE SET file_key = EXCLUDED.file_key""")
				.params(cover.bookId(), cover.fileKey())
				.update();
		}
		return covers.size();
	}

	/** COPY text format: {@code book_id \t {1,2,3} \t computed_on}. */
	private static long copy(Connection connection, String table, Iterator<Neighbours> rows, LocalDate computedOn)
			throws SQLException {
		CopyIn copy = connection.unwrap(PGConnection.class)
			.getCopyAPI()
			.copyIn("COPY " + table + " (book_id, neighbour_ids, computed_on) FROM STDIN");
		long count = 0;
		try {
			StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 512);
			String day = computedOn.toString();
			while (rows.hasNext()) {
				Neighbours row = rows.next();
				buffer.append(row.bookId()).append("\t{");
				int[] ids = row.neighbourIds();
				for (int i = 0; i < ids.length; i++) {
					if (i > 0) {
						buffer.append(',');
					}
					buffer.append(ids[i]);
				}
				buffer.append("}\t").append(day).append('\n');
				count++;
				if (buffer.length() >= FLUSH_CHARS) {
					write(copy, buffer);
				}
			}
			write(copy, buffer);
			copy.endCopy();
			return count;
		}
		finally {
			if (copy.isActive()) {
				copy.cancelCopy();
			}
		}
	}

	private static void write(CopyIn copy, StringBuilder buffer) throws SQLException {
		byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
		copy.writeToCopy(bytes, 0, bytes.length);
		buffer.setLength(0);
	}

	private static void execute(Connection connection, String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

}
