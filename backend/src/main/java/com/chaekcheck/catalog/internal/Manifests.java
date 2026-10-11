package com.chaekcheck.catalog.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.chaekcheck.catalog.CatalogLoad.Baseline;
import com.chaekcheck.catalog.CatalogLoad.Manifest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code catalog_next.load_manifest} (cycle 11, ADR-0078): one row made when the new tables are exported, dumped with
 * them, read back from the file without restoring it, and dropped before the swap. Also the baseline values two
 * catalogs are compared by.
 */
final class Manifests {

	/** The columns in the table's order, also the keys of the table comment's JSON. */
	static final List<String> COLUMNS = List.of("format_version", "export_id", "exported_at", "book_count",
			"book_max_id", "book_sequence", "holding_count", "holding_max_id", "holding_sequence", "columns_hash",
			"notes");

	/** Text of timestamptz with DateStyle ISO: {@code 2026-10-12 10:00:00.123456+09}. */
	private static final DateTimeFormatter TIMESTAMPTZ = new DateTimeFormatterBuilder()
		.append(DateTimeFormatter.ISO_LOCAL_DATE)
		.appendLiteral(' ')
		.append(DateTimeFormatter.ISO_LOCAL_TIME)
		.appendOffset("+HH:mm", "+00")
		.toFormatter();

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private Manifests() {
	}

	/**
	 * The baseline of {@code schema}'s four tables: row count, highest id and identity value of {@code book} and
	 * {@code holding}, and the hash of the columns.
	 */
	static Baseline baseline(Connection connection, String schema) throws SQLException {
		long[] book = idValues(connection, schema + ".book");
		long[] holding = idValues(connection, schema + ".holding");
		return new Baseline(book[0], book[1], book[2], holding[0], holding[1], holding[2],
				columnsHash(connection, schema));
	}

	/** count, max(id) or 0, and the identity's highest given value (as {@code JdbcCatalogLoad.Ids.highest}) */
	private static long[] idValues(Connection connection, String table) throws SQLException {
		String sequence;
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT pg_get_serial_sequence('" + table + "', 'id')")) {
			rs.next();
			sequence = rs.getString(1);
		}
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT (SELECT count(*) FROM " + table + "), (SELECT coalesce(max(id), 0)"
						+ " FROM " + table + "), (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM "
						+ sequence + ")")) {
			rs.next();
			return new long[] { rs.getLong(1), rs.getLong(2), rs.getLong(3) };
		}
	}

	/**
	 * SHA-256 (hex) of {@code table\tcolumn\tdata_type\tudt_name\n} for every column of the four tables, sorted by
	 * table and column name, from {@code information_schema.columns}.
	 */
	static String columnsHash(Connection connection, String schema) throws SQLException {
		StringBuilder text = new StringBuilder();
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT table_name, column_name, data_type, udt_name
				FROM information_schema.columns
				WHERE table_schema = ? AND table_name IN ('book', 'book_contributor', 'holding', 'catalog_library_snapshot')
				ORDER BY table_name COLLATE "C", column_name COLLATE "C"
				""")) {
			statement.setString(1, schema);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					text.append(rs.getString(1)).append('\t').append(rs.getString(2)).append('\t').append(rs.getString(3))
						.append('\t').append(rs.getString(4)).append('\n');
				}
			}
		}
		return sha256(text.toString());
	}

	static String sha256(String text) {
		try {
			return HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Creates the table in {@code catalog_next} with its one row. */
	static void create(Connection connection, Manifest manifest) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
					CREATE TABLE catalog_next.load_manifest (
					  format_version integer NOT NULL,
					  export_id uuid NOT NULL,
					  exported_at timestamp with time zone NOT NULL,
					  book_count bigint NOT NULL,
					  book_max_id bigint NOT NULL,
					  book_sequence bigint NOT NULL,
					  holding_count bigint NOT NULL,
					  holding_max_id bigint NOT NULL,
					  holding_sequence bigint NOT NULL,
					  columns_hash text NOT NULL,
					  notes jsonb NOT NULL
					)""");
		}
		Baseline b = manifest.baseline();
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_next.load_manifest VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""")) {
			statement.setInt(1, manifest.formatVersion());
			statement.setObject(2, manifest.exportId());
			statement.setTimestamp(3, Timestamp.from(manifest.exportedAt()));
			statement.setLong(4, b.bookCount());
			statement.setLong(5, b.bookMaxId());
			statement.setLong(6, b.bookSequence());
			statement.setLong(7, b.holdingCount());
			statement.setLong(8, b.holdingMaxId());
			statement.setLong(9, b.holdingSequence());
			statement.setString(10, b.columnsHash());
			statement.setString(11, manifest.notes() == null ? "{}" : manifest.notes());
			statement.executeUpdate();
		}
		// The same row as the table's comment: a comment is in the dump's table of contents at the start of the file,
		// so it is read without reading the data (the table data of load_manifest comes after the others, and a
		// piped dump has no data offsets: cycle 11 prototype 5)
		try (Statement statement = connection.createStatement()) {
			statement.execute("""
					DO $$ BEGIN
					  EXECUTE format('COMMENT ON TABLE catalog_next.load_manifest IS %L',
					    (SELECT row_to_json(m)::text FROM catalog_next.load_manifest m));
					END $$""");
		}
	}

	/** The row of the restored or waiting {@code catalog_next.load_manifest}. */
	static Manifest read(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(
				"SELECT " + String.join(", ", COLUMNS.stream().map(c -> c + "::text").toList())
						+ " FROM catalog_next.load_manifest")) {
			if (!rs.next()) {
				throw new IllegalStateException("catalog_next.load_manifest has no row");
			}
			Map<String, String> values = new HashMap<>();
			for (int i = 0; i < COLUMNS.size(); i++) {
				values.put(COLUMNS.get(i), rs.getString(i + 1));
			}
			if (rs.next()) {
				throw new IllegalStateException("catalog_next.load_manifest has more than one row");
			}
			return of(values);
		}
	}

	/**
	 * Reads the manifest from the output of {@code pg_restore -s -t load_manifest -f -}: the line
	 * {@code COMMENT ON TABLE catalog_next.load_manifest IS '<row as JSON>';}.
	 */
	static Manifest parse(String restoreOutput) {
		String prefix = "COMMENT ON TABLE catalog_next.load_manifest IS '";
		for (String line : restoreOutput.split("\r?\n")) {
			if (line.startsWith(prefix) && line.endsWith("';")) {
				String json = line.substring(prefix.length(), line.length() - 2).replace("''", "'");
				JsonNode row = JSON.readTree(json);
				Map<String, String> values = new HashMap<>();
				for (String column : COLUMNS) {
					JsonNode value = row.get(column);
					values.put(column, value == null || value.isNull() ? null
							: value.isValueNode() ? value.asString() : value.toString());
				}
				return of(values);
			}
		}
		throw new IllegalStateException("The file holds no catalog_next.load_manifest: is it an export file?");
	}

	private static Manifest of(Map<String, String> values) {
		for (String column : COLUMNS) {
			if (values.get(column) == null) {
				throw new IllegalStateException("load_manifest." + column + " is missing");
			}
		}
		int version = Integer.parseInt(values.get("format_version"));
		if (version != JdbcCatalogLoad.FORMAT_VERSION) {
			throw new IllegalStateException("The file has manifest format " + version + "; this version reads "
					+ JdbcCatalogLoad.FORMAT_VERSION);
		}
		Baseline baseline = new Baseline(Long.parseLong(values.get("book_count")),
				Long.parseLong(values.get("book_max_id")), Long.parseLong(values.get("book_sequence")),
				Long.parseLong(values.get("holding_count")), Long.parseLong(values.get("holding_max_id")),
				Long.parseLong(values.get("holding_sequence")), values.get("columns_hash"));
		return new Manifest(version, UUID.fromString(values.get("export_id")), timestamp(values.get("exported_at")),
				baseline, values.get("notes"));
	}

	/** {@code ::text} (DateStyle ISO) or JSON ({@code 2026-10-12T10:00:00.123456+09:00}) of a timestamptz. */
	static Instant timestamp(String text) {
		return text.indexOf('T') > 0 ? OffsetDateTime.parse(text).toInstant()
				: OffsetDateTime.parse(text, TIMESTAMPTZ).toInstant();
	}

}
