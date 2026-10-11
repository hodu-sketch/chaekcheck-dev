package com.chaekcheck.catalog.internal;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chaekcheck.catalog.CatalogLoad;
import com.chaekcheck.catalog.normalize.Contributors.Contributor;

/**
 * {@link CatalogLoad} with PostgreSQL COPY (ADR-0060), written into new tables and swapped in (plan ADR-0065, cycle
 * 08). While it runs, the search keeps reading the previous {@code catalog} tables; it waits only for the swap.
 * <ol>
 * <li>The loader's books, contributors and holdings go into temporary tables ({@code load_*}). Only one COPY can run
 * on a connection, so the contributors wait in a temporary file until the first holding arrives.</li>
 * <li>Ids: one narrow match of the new holdings with the previous ones on {@code (library_id, item_key)} decides the
 * book ids (plan {@code catalog-refresh.md} 5.2, ADR-0062) and the holding ids (ADR-0070: the same holding keeps its
 * id, new holdings get ids above every id given before).</li>
 * <li>Write: the four tables are created in the schema {@code catalog_next} with the columns, defaults and identity of
 * the {@code catalog} tables but no index or constraint, and filled in book id order (holdings sorted by book, R8).</li>
 * <li>Index: every index and constraint of the {@code catalog} tables is made again under the same name from its
 * definition ({@code pg_get_indexdef}, {@code pg_get_constraintdef}), the schema name changed.</li>
 * <li>{@code VACUUM (ANALYZE)} of the new tables.</li>
 * <li>Swap, in one short transaction with a lock timeout: the old tables are dropped and the new ones moved into
 * {@code catalog}. When the lock is not granted it waits and tries again. A foreign key from outside {@code catalog}
 * to these tables stops the swap (handoff C2).</li>
 * </ol>
 * Any failure before the swap commits drops {@code catalog_next} and leaves the previous tables as they were.
 */
class JdbcCatalogLoad implements CatalogLoad {

	private static final Logger log = LoggerFactory.getLogger(JdbcCatalogLoad.class);

	private static final int FLUSH_CHARS = 1 << 20;

	/** Children first: the order in which the old tables can be dropped. */
	static final List<String> TABLES = List.of("book_contributor", "holding", "catalog_library_snapshot", "book");

	static final String NEXT = "catalog_next";

	/** The one-row table of an export (cycle 11, ADR-0078); only in {@code catalog_next}, never in Liquibase. */
	static final String MANIFEST = NEXT + ".load_manifest";

	/** The {@code load_manifest} layout; a file of another version is refused. */
	static final int FORMAT_VERSION = 1;

	static final String WAITING_MESSAGE = "New tables of an export are waiting in " + NEXT
			+ ": first finish them (after the production apply) or discard them";

	/** PostgreSQL {@code lock_not_available}: the lock timeout ran out. */
	private static final String LOCK_NOT_AVAILABLE = "55P03";

	private final DataSource dataSource;

	private final CatalogLoadSettings settings;

	/** Null when {@code pg-command} is not set: export, apply and finish do not start then. */
	private final PgTools tools;

	JdbcCatalogLoad(DataSource dataSource, CatalogLoadSettings settings, PgTools tools) {
		this.dataSource = dataSource;
		this.settings = settings;
		this.tools = tools;
	}

	@Override
	public Result replaceAll(String source, Loader loader) {
		refuseWhenWaiting();
		Map<String, Duration> steps = new LinkedHashMap<>();
		return run("load from " + source, true, connection -> {
			Counts counts = prepare(connection, loader, steps);
			index(connection, steps);
			analyze(connection, steps);
			swap(connection, steps, List.of());
			return counts.result(steps);
		});
	}

	@Override
	public Result prepare(String source, Loader loader) {
		refuseWhenWaiting();
		Map<String, Duration> steps = new LinkedHashMap<>();
		return run("prepare from " + source, true, connection -> prepare(connection, loader, steps).result(steps));
	}

	@Override
	public Manifest export(Path file, String notes) {
		Path part = file.resolveSibling(file.getFileName() + ".part");
		if (Files.exists(file)) {
			throw new IllegalStateException("The export file exists already: " + file);
		}
		return run("export " + file.getFileName(), true, connection -> {
			if (!exists(connection, NEXT + ".book")) {
				throw new IllegalStateException("No new tables to export: run the import with mode export");
			}
			if (exists(connection, MANIFEST)) {
				throw new IllegalStateException("The new tables were exported already; finish or discard them first");
			}
			Manifest manifest = new Manifest(FORMAT_VERSION, UUID.randomUUID(),
					Instant.now().truncatedTo(ChronoUnit.MICROS), Manifests.baseline(connection, "catalog"), notes);
			Manifests.create(connection, manifest);
			Files.deleteIfExists(part);
			tools().dump(database(connection), part);
			Manifest read = Manifests.parse(tools().manifestDefinition(part));
			if (!read.exportId().equals(manifest.exportId())) {
				throw new IllegalStateException("The dump holds export " + read.exportId() + ", not "
						+ manifest.exportId() + ": does pg-command reach this database?");
			}
			Files.move(part, file, StandardCopyOption.ATOMIC_MOVE);
			log.info("exported {} ({} bytes), export_id {}", file, Files.size(file), manifest.exportId());
			return read;
		});
	}

	@Override
	public Manifest readManifest(Path file) {
		if (!Files.isRegularFile(file)) {
			throw new IllegalArgumentException("No export file: " + file);
		}
		return Manifests.parse(tools().manifestDefinition(file));
	}

	@Override
	public Baseline baseline() {
		try (Connection connection = dataSource.getConnection()) {
			return Manifests.baseline(connection, "catalog");
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Cannot read the catalog baseline", ex);
		}
	}

	@Override
	public Result apply(Path file) {
		Manifest manifest = readManifest(file);
		Baseline here = baseline();
		if (!here.equals(manifest.baseline())) {
			throw new NotSwappedException("mismatch",
					"The file was made from another catalog: this database " + here + ", file " + manifest.baseline());
		}
		Map<String, Duration> steps = new LinkedHashMap<>();
		return run("apply " + file.getFileName(), true, connection -> {
			// left by an apply that stopped
			endLeftRestores(connection);
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA IF EXISTS " + NEXT + " CASCADE");
			}
			long started = System.nanoTime();
			tools().restore(database(connection), file);
			steps.put("restore", since(started));
			Manifest restored = Manifests.read(connection);
			if (!restored.exportId().equals(manifest.exportId())) {
				throw new IllegalStateException("The restored tables hold export " + restored.exportId() + ", not "
						+ manifest.exportId() + ": does pg-command reach this database?");
			}
			// the swap's DROP SCHEMA runs without CASCADE
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP TABLE " + MANIFEST);
			}
			index(connection, steps);
			analyze(connection, steps);
			swap(connection, steps, List.of());
			return restoredResult(connection, steps);
		});
	}

	@Override
	public Result finishPrepared(Path file) {
		Manifest manifest = readManifest(file);
		Map<String, Duration> steps = new LinkedHashMap<>();
		return run("finish " + file.getFileName(), false, connection -> {
			if (!exists(connection, MANIFEST)) {
				throw new IllegalStateException("No new tables are waiting here. If the production apply went well,"
						+ " restore the same file here with mode apply");
			}
			Manifest waiting = Manifests.read(connection);
			if (!waiting.exportId().equals(manifest.exportId()) || !waiting.baseline().equals(manifest.baseline())) {
				throw new IllegalStateException("The waiting tables are export " + waiting.exportId() + ", not the file's "
						+ manifest.exportId() + ". Restore the file here with mode apply, or discard");
			}
			dropIndexesAndConstraints(connection);
			index(connection, steps);
			analyze(connection, steps);
			swap(connection, steps, List.of("DROP TABLE " + MANIFEST));
			return restoredResult(connection, steps);
		});
	}

	@Override
	public Discarded discardPrepared() {
		try (Connection connection = dataSource.getConnection()) {
			connection.setAutoCommit(true);
			Discarded what = !exists(connection, NEXT + ".book") && !schemaExists(connection) ? Discarded.NOTHING
					: exists(connection, MANIFEST) ? Discarded.WAITING : Discarded.LEFT_OVER;
			endLeftRestores(connection);
			if (what == Discarded.WAITING) {
				log.warn("discarding new tables waiting for the finish: do this only when the production apply failed");
			}
			try (Statement statement = connection.createStatement()) {
				statement.execute("DROP SCHEMA IF EXISTS " + NEXT + " CASCADE");
			}
			log.info("discard: {}", what);
			return what;
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Cannot drop " + NEXT, ex);
		}
	}

	@Override
	public boolean hasWaitingExport() {
		try (Connection connection = dataSource.getConnection()) {
			return exists(connection, MANIFEST);
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Cannot check " + NEXT, ex);
		}
	}

	/**
	 * Ends restores of this database a stopped apply left running (their transaction rolls back). The restore's input
	 * comes from the Java process, so it normally ends with it (cycle 11 prototype 4); this is for the rest.
	 */
	private static void endLeftRestores(Connection connection) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT pid, pg_terminate_backend(pid, 10000) FROM pg_stat_activity
				WHERE application_name = ? AND datname = current_database() AND pid <> pg_backend_pid()""")) {
			statement.setString(1, PgTools.RESTORE_APPLICATION);
			try (ResultSet rs = statement.executeQuery()) {
				while (rs.next()) {
					log.warn("ended a restore left running (pid {}, ended {})", rs.getInt(1), rs.getBoolean(2));
				}
			}
		}
	}

	/** Waiting new tables are never dropped by a new load (cycle 11 C6). */
	private void refuseWhenWaiting() {
		if (hasWaitingExport()) {
			throw new IllegalStateException(WAITING_MESSAGE);
		}
	}

	private PgTools tools() {
		if (tools == null) {
			throw new IllegalStateException("Set chaekcheck.catalog.load.pg-command (see docs/maintenance/3-import.md)");
		}
		return tools;
	}

	/** The counts of the tables just swapped in; no ids were matched here. */
	private static Result restoredResult(Connection connection, Map<String, Duration> steps) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT (SELECT count(*) FROM catalog.book), (SELECT count(*) FROM catalog.book_contributor),
				  (SELECT count(*) FROM catalog.holding), (SELECT count(*) FROM catalog.catalog_library_snapshot)""")) {
			rs.next();
			return new Result(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getLong(4), 0, 0, 0, steps);
		}
	}

	private static PgTools.Database database(Connection connection) throws SQLException {
		return new PgTools.Database(connection.getCatalog(), connection.getMetaData().getUserName());
	}

	static boolean exists(Connection connection, String table) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT to_regclass(?) IS NOT NULL")) {
			statement.setString(1, table);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getBoolean(1);
			}
		}
	}

	private static boolean schemaExists(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT to_regnamespace('" + NEXT + "') IS NOT NULL")) {
			rs.next();
			return rs.getBoolean(1);
		}
	}

	/**
	 * Before a finish runs again: the indexes and constraints (but NOT NULL) a stopped finish made on the
	 * {@code catalog_next} tables, the manifest's table left out.
	 */
	private static void dropIndexesAndConstraints(Connection connection) throws SQLException {
		List<String> statements = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT t.relname, c.conname
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'catalog_next' AND t.relname <> 'load_manifest' AND c.contype IN ('p', 'u', 'x', 'c', 'f')
				ORDER BY CASE c.contype WHEN 'f' THEN 0 ELSE 1 END, t.relname, c.conname""")) {
			while (rs.next()) {
				statements.add("ALTER TABLE " + NEXT + "." + rs.getString(1) + " DROP CONSTRAINT IF EXISTS "
						+ rs.getString(2) + " CASCADE");
			}
		}
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT x.relname
				FROM pg_index i
				JOIN pg_class t ON t.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				JOIN pg_class x ON x.oid = i.indexrelid
				WHERE n.nspname = 'catalog_next' AND t.relname <> 'load_manifest'
				  AND NOT EXISTS (SELECT 1 FROM pg_constraint c WHERE c.conindid = i.indexrelid)
				ORDER BY x.relname""")) {
			while (rs.next()) {
				statements.add("DROP INDEX IF EXISTS " + NEXT + "." + rs.getString(1));
			}
		}
		try (Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				statement.execute(sql);
				log.info("finish again: {}", sql);
			}
		}
	}

	/** Work on one connection of the load; the connection is in auto-commit mode with the load's session settings. */
	@FunctionalInterface
	private interface Work<T> {

		T run(Connection connection) throws Exception;

	}

	/**
	 * Runs one load on its own connection.
	 * @param dropOnFailure drop {@code catalog_next} when the work fails (ADR-0065); false keeps the new tables
	 */
	private <T> T run(String source, boolean dropOnFailure, Work<T> work) {
		try (Connection connection = dataSource.getConnection()) {
			boolean autoCommit = connection.getAutoCommit();
			try {
				connection.setAutoCommit(true);
				applySessionSettings(connection);
				T result = work.run(connection);
				log.info("catalog {}: {}", source, result);
				return result;
			}
			catch (Exception ex) {
				cleanUp(connection, dropOnFailure);
				if (ex instanceof RuntimeException runtime) {
					throw runtime;
				}
				throw new IllegalStateException("Catalog " + source + " failed", ex);
			}
			finally {
				connection.setAutoCommit(autoCommit);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException("Catalog " + source + " failed", ex);
		}
	}

	/** Settings of this session only: the service's settings stay as they are. */
	private void applySessionSettings(Connection connection) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			// the definitions read below name every schema, whatever the server's search_path is
			statement.execute("SET search_path = pg_catalog, public");
			if (settings.maintenanceWorkMem() != null) {
				statement.execute("SET maintenance_work_mem = '" + settings.maintenanceWorkMem() + "'");
			}
			if (settings.maxParallelMaintenanceWorkers() != null) {
				statement.execute("SET max_parallel_maintenance_workers = " + settings.maxParallelMaintenanceWorkers());
			}
			if (settings.workMem() != null) {
				statement.execute("SET work_mem = '" + settings.workMem() + "'");
			}
		}
	}

	/**
	 * Fills {@code catalog_next} without indexes or constraints and commits: temporary tables, ids, write (steps
	 * {@code books_copy} to {@code write}). The temporary tables are dropped afterwards.
	 */
	private Counts prepare(Connection connection, Loader loader, Map<String, Duration> steps) throws Exception {
		try (Statement statement = connection.createStatement()) {
			// left by a load that stopped (ADR-0065)
			statement.execute("DROP SCHEMA IF EXISTS " + NEXT + " CASCADE");
			statement.execute("CREATE SCHEMA " + NEXT);
			// LIKE copies the columns, NOT NULL and defaults; the catalog tables' checks come with the new tables
			statement.execute("""
					CREATE TEMPORARY TABLE load_book (LIKE catalog.book INCLUDING DEFAULTS);
					CREATE TEMPORARY TABLE load_book_contributor (LIKE catalog.book_contributor INCLUDING DEFAULTS);
					CREATE TEMPORARY TABLE load_holding (LIKE catalog.holding INCLUDING DEFAULTS)""");
		}

		connection.setAutoCommit(false);
		Counts counts;
		try (LoadSession session = new LoadSession(connection, steps)) {
			loader.load(session);
			session.finish();
			long started = System.nanoTime();
			Ids ids = Ids.decide(connection);
			steps.put("match", since(started));
			started = System.nanoTime();
			write(connection, session.snapshots, ids);
			connection.commit();
			steps.put("write", since(started));
			counts = new Counts(session.bookCount, session.contributorCount, session.holdingCount,
					session.snapshots.size(), ids);
		}
		catch (Exception ex) {
			connection.rollback();
			throw ex;
		}
		connection.setAutoCommit(true);
		try (Statement statement = connection.createStatement()) {
			statement.execute("DROP TABLE load_book_contributor, load_holding, load_book, load_book_id, load_match");
		}
		return counts;
	}

	/** Step {@code index}: the indexes and constraints of {@code catalog}, made on the {@code catalog_next} tables. */
	private static void index(Connection connection, Map<String, Duration> steps) throws SQLException {
		long started = System.nanoTime();
		createIndexesAndConstraints(connection);
		steps.put("index", since(started));
	}

	/** Step {@code analyze}: {@code VACUUM (ANALYZE)} of the new tables. */
	private static void analyze(Connection connection, Map<String, Duration> steps) throws SQLException {
		long started = System.nanoTime();
		try (Statement statement = connection.createStatement()) {
			// VACUUM cannot run inside a transaction (ADR-0054)
			statement.execute("VACUUM (ANALYZE) " + String.join(", ", TABLES.stream().map(t -> NEXT + "." + t).toList()));
		}
		steps.put("analyze", since(started));
	}

	/**
	 * Step {@code swap}.
	 * @param first statements run first in the swap's transaction (the finish drops {@code load_manifest} there)
	 */
	private void swap(Connection connection, Map<String, Duration> steps, List<String> first)
			throws SQLException, InterruptedException {
		long started = System.nanoTime();
		swap(connection, first);
		steps.put("swap", since(started));
	}

	/** The four tables in {@code catalog_next}, without indexes or constraints, filled in book id order. */
	private static void write(Connection connection, List<Object[]> snapshots, Ids ids) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			for (String table : TABLES) {
				// NOT NULL always comes with LIKE; the identity gets its own sequence, moved with the table at the swap
				statement.execute("CREATE TABLE " + NEXT + "." + table + " (LIKE catalog." + table
						+ " INCLUDING DEFAULTS INCLUDING IDENTITY INCLUDING GENERATED INCLUDING STORAGE"
						+ " INCLUDING COMPRESSION)");
			}
			statement.execute("""
					INSERT INTO catalog_next.book (id, title, author_display, publisher, title_norm, main_title_norm,
					  publisher_norm, published_on, isbn13, content_type, library_ids)
					SELECT m.book_id, b.title, b.author_display, b.publisher, b.title_norm, b.main_title_norm,
					  b.publisher_norm, b.published_on, b.isbn13, b.content_type, b.library_ids
					FROM load_book b JOIN load_book_id m ON m.load_id = b.id
					ORDER BY m.book_id""");
			statement.execute("""
					INSERT INTO catalog_next.book_contributor (id, book_id, name, alias, name_norm, name_exact, role,
					  position)
					SELECT c.id, m.book_id, c.name, c.alias, c.name_norm, c.name_exact, c.role, c.position
					FROM load_book_contributor c JOIN load_book_id m ON m.load_id = c.book_id
					ORDER BY m.book_id, c.position, c.id""");
			// R8: holdings in book order, so the rows of one book share pages (pg_stats correlation of book_id)
			statement.execute("""
					INSERT INTO catalog_next.holding (id, book_id, library_id, platform, item_key, detail_url,
					  content_type, title_raw, author_raw, publisher_raw, published_raw, supplier, alt_code,
					  first_seen_at)
					SELECT x.holding_id, m.book_id, h.library_id, h.platform, h.item_key, h.detail_url, h.content_type,
					  h.title_raw, h.author_raw, h.publisher_raw, h.published_raw, h.supplier, h.alt_code,
					  h.first_seen_at
					FROM load_holding h
					JOIN load_match x ON x.load_id = h.id
					JOIN load_book_id m ON m.load_id = h.book_id
					ORDER BY m.book_id, h.library_id, x.holding_id""");
			// the next ids: books and holdings continue above every id given before (ADR-0062, ADR-0070)
			statement.execute("SELECT setval(pg_get_serial_sequence('catalog_next.book', 'id'), "
					+ Math.max(ids.highestBook(), 1) + ", " + (ids.highestBook() > 0) + ")");
			statement.execute("SELECT setval(pg_get_serial_sequence('catalog_next.holding', 'id'), "
					+ Math.max(ids.highestHolding(), 1) + ", " + (ids.highestHolding() > 0) + ")");
			statement.execute("SELECT setval(pg_get_serial_sequence('catalog_next.book_contributor', 'id'),"
					+ " coalesce((SELECT max(id) FROM catalog_next.book_contributor), 0) + 1, false)");
		}
		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO catalog_next.catalog_library_snapshot (library_id, collected_at) VALUES (?, ?)""")) {
			for (Object[] snapshot : snapshots) {
				statement.setLong(1, (Long) snapshot[0]);
				statement.setTimestamp(2, Timestamp.from((Instant) snapshot[1]));
				statement.addBatch();
			}
			statement.executeBatch();
		}
	}

	/**
	 * Every index and constraint of the {@code catalog} tables, made again on the {@code catalog_next} tables under
	 * the same names: primary keys and unique constraints first, then checks, then the foreign keys (they need the
	 * primary key), then the other indexes. NOT NULL constraints came with the tables.
	 */
	static void createIndexesAndConstraints(Connection connection) throws SQLException {
		List<String> statements = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT t.relname, c.conname, pg_get_constraintdef(c.oid)
				FROM pg_constraint c
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				WHERE n.nspname = 'catalog' AND c.contype IN ('p', 'u', 'x', 'c', 'f')
				ORDER BY CASE c.contype WHEN 'p' THEN 0 WHEN 'u' THEN 1 WHEN 'x' THEN 1 WHEN 'c' THEN 2 ELSE 3 END,
				  t.relname, c.conname""")) {
			while (rs.next()) {
				String definition = rs.getString(3).replace("REFERENCES catalog.", "REFERENCES " + NEXT + ".");
				statements.add("ALTER TABLE " + NEXT + "." + rs.getString(1) + " ADD CONSTRAINT " + rs.getString(2) + " "
						+ definition);
			}
		}
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT pg_get_indexdef(i.indexrelid)
				FROM pg_index i
				JOIN pg_class t ON t.oid = i.indrelid
				JOIN pg_namespace n ON n.oid = t.relnamespace
				JOIN pg_class x ON x.oid = i.indexrelid
				WHERE n.nspname = 'catalog'
				  AND NOT EXISTS (SELECT 1 FROM pg_constraint c
				    WHERE c.conindid = i.indexrelid AND c.contype IN ('p', 'u', 'x'))
				ORDER BY t.relname, x.relname""")) {
			while (rs.next()) {
				String definition = rs.getString(1);
				String moved = definition.replaceFirst(" ON catalog\\.", " ON " + NEXT + ".");
				if (moved.equals(definition)) {
					throw new IllegalStateException("Cannot move the index definition: " + definition);
				}
				statements.add(moved);
			}
		}
		try (Statement statement = connection.createStatement()) {
			for (String sql : statements) {
				long started = System.nanoTime();
				statement.execute(sql);
				log.info("{} ({} s)", sql, since(started).toMillis() / 1000.0);
			}
		}
	}

	/**
	 * Drops the old tables and moves the new ones into {@code catalog} in one transaction. The DROP needs an exclusive
	 * lock on tables the search is reading; {@code lock_timeout} keeps the wait short, and the swap is tried again
	 * after a rest (ADR-0065).
	 */
	private void swap(Connection connection, List<String> first) throws SQLException, InterruptedException {
		List<String> outside = outsideForeignKeys(connection);
		if (!outside.isEmpty()) {
			throw new NotSwappedException("outside_foreign_key",
					"Foreign keys from outside the catalog schema point at its tables: " + outside);
		}
		connection.setAutoCommit(false);
		try {
			for (int attempt = 1;; attempt++) {
				try (Statement statement = connection.createStatement()) {
					statement.execute("SET LOCAL lock_timeout = '" + settings.lockTimeout().toMillis() + "ms'");
					for (String sql : first) {
						statement.execute(sql);
					}
					statement.execute("DROP TABLE " + String.join(", ", TABLES.stream().map(t -> "catalog." + t).toList()));
					for (String table : TABLES) {
						statement.execute("ALTER TABLE " + NEXT + "." + table + " SET SCHEMA catalog");
					}
					statement.execute("DROP SCHEMA " + NEXT);
					connection.commit();
					return;
				}
				catch (SQLException ex) {
					connection.rollback();
					if (!LOCK_NOT_AVAILABLE.equals(ex.getSQLState())) {
						throw ex;
					}
					if (attempt >= settings.swapAttempts()) {
						throw new NotSwappedException("lock_not_acquired",
								"The catalog tables stayed locked through " + attempt + " swap attempts");
					}
					log.warn("catalog swap: lock not granted in {} (attempt {} of {}), trying again in {}",
							settings.lockTimeout(), attempt, settings.swapAttempts(), settings.swapRetryWait());
					Thread.sleep(settings.swapRetryWait().toMillis());
				}
			}
		}
		finally {
			connection.setAutoCommit(true);
		}
	}

	/** Foreign keys of tables outside {@code catalog} and {@code catalog_next} that point at the catalog tables (C2). */
	static List<String> outsideForeignKeys(Connection connection) throws SQLException {
		List<String> found = new ArrayList<>();
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery("""
				SELECT c.conrelid::regclass::text || '.' || c.conname
				FROM pg_constraint c
				JOIN pg_class r ON r.oid = c.confrelid
				JOIN pg_namespace rn ON rn.oid = r.relnamespace
				JOIN pg_class t ON t.oid = c.conrelid
				JOIN pg_namespace tn ON tn.oid = t.relnamespace
				WHERE c.contype = 'f' AND rn.nspname = 'catalog'
				  AND tn.nspname NOT IN ('catalog', 'catalog_next')
				ORDER BY 1""")) {
			while (rs.next()) {
				found.add(rs.getString(1));
			}
		}
		return found;
	}

	/**
	 * After a failure: the transaction is rolled back and the temporary tables are dropped.
	 * @param dropNext drop the new schema too; false keeps waiting new tables (the finish, ADR-0078)
	 */
	private static void cleanUp(Connection connection, boolean dropNext) {
		try {
			if (!connection.getAutoCommit()) {
				connection.rollback();
			}
			connection.setAutoCommit(true);
			try (Statement statement = connection.createStatement()) {
				if (dropNext) {
					statement.execute("DROP SCHEMA IF EXISTS " + NEXT + " CASCADE");
				}
				statement.execute("DROP TABLE IF EXISTS load_book_contributor, load_holding, load_book, load_book_id,"
						+ " load_match");
			}
		}
		catch (SQLException ex) {
			log.warn("catalog load: could not drop {} after the failure; the next load drops it", NEXT, ex);
		}
	}

	private static Duration since(long nanoTime) {
		return Duration.ofNanos(System.nanoTime() - nanoTime);
	}

	private record Counts(long books, long contributors, long holdings, long snapshots, Ids ids) {

		Result result(Map<String, Duration> steps) {
			return new Result(books, contributors, holdings, snapshots, ids.kept(), ids.added(), ids.dropped(), steps);
		}

	}

	private static final class LoadSession implements Session, AutoCloseable {

		private final Connection connection;

		private final CopyManager copy;

		private final Path contributorFile;

		private final BufferedWriter contributors;

		final List<Object[]> snapshots = new ArrayList<>();

		/** Step times (see {@link Result#steps()}); the holdings' COPY time is summed inside {@link Copy}. */
		private final Map<String, Duration> steps;

		private final long started = System.nanoTime();

		private long holdingsStarted;

		private Copy books;

		private Copy holdings;

		long bookCount;

		long contributorCount;

		long holdingCount;

		LoadSession(Connection connection, Map<String, Duration> steps) throws SQLException, IOException {
			this.connection = connection;
			this.steps = steps;
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
					holdingsStarted = System.nanoTime();
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

		/** Ends the COPYs. */
		void finish() throws SQLException, IOException {
			if (holdings == null) {
				endBooks();
				holdingsStarted = System.nanoTime();
			}
			else {
				holdings.close();
			}
			Duration copied = holdings == null ? Duration.ZERO : Duration.ofNanos(holdings.copyNanos);
			holdings = null;
			steps.put("holdings_read", since(holdingsStarted).minus(copied));
			steps.put("holdings_copy", copied);
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
			steps.put("books_copy", since(started));
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
	 * Decides the stored ids from one match of the new holdings with the previous ones on the unique
	 * {@code (library_id, item_key)}, while the previous tables are still in {@code catalog}:
	 * <ul>
	 * <li>{@code load_match (load_id, load_book_id, old_book_id, holding_id)}: every new holding; a holding found
	 * before keeps its id, a new one gets an id above every holding id given before (ADR-0070).</li>
	 * <li>{@code load_book_id (load_id, book_id)}: plan {@code catalog-refresh.md} 5.2. Each old book goes to the new
	 * book sharing most of its holdings (ties to the smaller load id); a new book several old books chose keeps the
	 * smallest old id; the other books get new ids above every book id given before (ADR-0062).</li>
	 * </ul>
	 * @param highestBook the highest book id given so far, new ones included
	 * @param highestHolding the highest holding id given so far, new ones included
	 */
	record Ids(long kept, long added, long dropped, long highestBook, long highestHolding) {

		static Ids decide(Connection connection) throws SQLException {
			try (Statement statement = connection.createStatement()) {
				// temporary tables are never analyzed on their own; the joins below need the row counts
				statement.execute("ANALYZE load_book, load_holding");
				long highestHolding = highest(statement, "catalog.holding");
				statement.execute("""
						CREATE TEMPORARY TABLE load_match AS
						SELECT n.id AS load_id, n.book_id AS load_book_id, o.book_id AS old_book_id,
						  coalesce(o.id, %d + row_number() OVER (PARTITION BY o.id IS NULL ORDER BY n.id)) AS holding_id
						FROM load_holding n
						LEFT JOIN catalog.holding o ON o.library_id = n.library_id AND o.item_key = n.item_key"""
					.formatted(highestHolding));
				long newHoldings = count(statement, "SELECT count(*) FROM load_match WHERE old_book_id IS NULL");
				statement.execute("""
						CREATE TEMPORARY TABLE load_book_id AS
						WITH shared AS (
						  SELECT load_book_id AS load_id, old_book_id AS old_id, count(*) AS holdings
						  FROM load_match WHERE old_book_id IS NOT NULL
						  GROUP BY load_book_id, old_book_id
						), best AS (
						  SELECT DISTINCT ON (old_id) old_id, load_id FROM shared ORDER BY old_id, holdings DESC, load_id
						)
						SELECT load_id, min(old_id) AS book_id FROM best GROUP BY load_id""");
				long kept = count(statement, "SELECT count(*) FROM load_book_id");
				long oldBooks = count(statement, "SELECT count(*) FROM catalog.book");
				long highestBook = highest(statement, "catalog.book");
				long added = statement.executeUpdate("""
						INSERT INTO load_book_id (load_id, book_id)
						SELECT n.id, %d + row_number() OVER (ORDER BY n.id) FROM load_book n
						WHERE NOT EXISTS (SELECT 1 FROM load_book_id m WHERE m.load_id = n.id)""".formatted(highestBook));
				statement.execute("ANALYZE load_match, load_book_id");
				return new Ids(kept, added, oldBooks - kept, highestBook + added, highestHolding + newHoldings);
			}
		}

		/** The highest id the table or its identity sequence ever had: ids are never given twice. */
		private static long highest(Statement statement, String table) throws SQLException {
			String sequence;
			try (ResultSet rs = statement.executeQuery("SELECT pg_get_serial_sequence('" + table + "', 'id')")) {
				rs.next();
				sequence = rs.getString(1);
			}
			return count(statement, "SELECT greatest(coalesce((SELECT max(id) FROM " + table + "), 0),"
					+ " (SELECT CASE WHEN is_called THEN last_value ELSE last_value - 1 END FROM " + sequence + "))");
		}

		private static long count(Statement statement, String sql) throws SQLException {
			try (ResultSet rs = statement.executeQuery(sql)) {
				rs.next();
				return rs.getLong(1);
			}
		}

	}

	/** COPY text format writer: tab separated, {@code \N} for null, backslash escapes. */
	private static final class Copy {

		private final CopyIn in;

		private final StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 4096);

		/** Time spent handing rows to the server, for the step times. */
		long copyNanos;

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
			long started = System.nanoTime();
			byte[] bytes = buffer.toString().getBytes(StandardCharsets.UTF_8);
			in.writeToCopy(bytes, 0, bytes.length);
			buffer.setLength(0);
			copyNanos += System.nanoTime() - started;
		}

		void close() throws SQLException {
			flush();
			long started = System.nanoTime();
			in.endCopy();
			copyNanos += System.nanoTime() - started;
		}

		void cancel() throws SQLException {
			if (in.isActive()) {
				in.cancelCopy();
			}
		}

	}

}
