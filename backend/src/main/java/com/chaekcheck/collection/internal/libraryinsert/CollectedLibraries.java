package com.chaekcheck.collection.internal.libraryinsert;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.chaekcheck.collection.internal.importer.HoldingRowFilter;

/**
 * The libraries of a collection DB copy, read-only (plan cycle 13 R2-3; like {@code CollectedCatalog}, plain JDBC with
 * {@code open_mode=1}). Also counts the item keys two libraries share, for the same-host check (R2-7).
 */
class CollectedLibraries implements LibraryInsertPlanner.Holdings, AutoCloseable {

	/** One {@code library} row. {@code vendor} is null on copies made before the column (2026-10-12). */
	record CollectedLibrary(long id, String name, String platformType, String baseUrl, String lastFullSuccessAt,
			String crawlSkip, String vendor) {
	}

	private final Connection connection;

	private CollectedLibraries(Connection connection) {
		this.connection = connection;
	}

	static CollectedLibraries open(Path sqlite) throws SQLException {
		if (!Files.isRegularFile(sqlite)) {
			throw new IllegalArgumentException("Collection DB copy not found: " + sqlite);
		}
		Properties props = new Properties();
		props.setProperty("open_mode", "1");
		return new CollectedLibraries(DriverManager.getConnection("jdbc:sqlite:" + sqlite.toAbsolutePath(), props));
	}

	List<CollectedLibrary> libraries() throws SQLException {
		Set<String> columns = new HashSet<>();
		try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_info(library)");
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				columns.add(rs.getString("name"));
			}
		}
		String vendor = columns.contains("vendor") ? "vendor" : "NULL AS vendor";
		String skip = columns.contains("crawl_skip") ? "crawl_skip" : "NULL AS crawl_skip";
		List<CollectedLibrary> libraries = new ArrayList<>();
		try (PreparedStatement statement = connection.prepareStatement("SELECT id, name, platform_type, base_url, "
				+ "last_full_success_at, " + skip + ", " + vendor + " FROM library ORDER BY id");
				ResultSet rs = statement.executeQuery()) {
			while (rs.next()) {
				libraries.add(new CollectedLibrary(rs.getLong("id"), rs.getString("name"), rs.getString("platform_type"),
						rs.getString("base_url"), rs.getString("last_full_success_at"), rs.getString("crawl_skip"),
						rs.getString("vendor")));
			}
		}
		return libraries;
	}

	@Override
	public long keys(long collectionId) {
		return count("SELECT count(*) FROM holding WHERE library_id = ? AND removed_at IS NULL", collectionId, null);
	}

	@Override
	public long shared(long collectionId, long otherCollectionId) {
		return count("""
				SELECT count(*) FROM holding a JOIN holding b ON b.library_id = ? AND b.item_key = a.item_key
				  AND b.removed_at IS NULL
				WHERE a.library_id = ? AND a.removed_at IS NULL""", otherCollectionId, collectionId);
	}

	/**
	 * Rows of one library that pass {@link HoldingRowFilter}, the import's own rule (cycle 18, ADR-0110). Reads only
	 * that library's rows ({@code holding} is indexed by library).
	 */
	@Override
	public long importableRows(long collectionId, String libraryHost) {
		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT removed_at, content_type, content_kind, detail_url, title FROM holding WHERE library_id = ?")) {
			statement.setLong(1, collectionId);
			long rows = 0;
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					if (HoldingRowFilter.skipReason(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4),
							rs.getString(5), libraryHost) == null) {
						rows++;
					}
				}
			}
			return rows;
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Reading the collection DB copy failed", ex);
		}
	}

	private long count(String sql, long first, Long second) {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			statement.setLong(1, first);
			if (second != null) {
				statement.setLong(2, second);
			}
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Reading the collection DB copy failed", ex);
		}
	}

	@Override
	public void close() throws SQLException {
		connection.close();
	}

}
