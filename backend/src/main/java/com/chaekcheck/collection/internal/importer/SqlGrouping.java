package com.chaekcheck.collection.internal.importer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import javax.sql.DataSource;

import org.postgresql.PGConnection;
import org.postgresql.copy.CopyIn;
import org.postgresql.copy.CopyManager;

import com.chaekcheck.catalog.normalize.CatalogText;
import com.chaekcheck.catalog.normalize.Contributors;
import com.chaekcheck.collection.internal.importer.CollectedCatalog.Row;

/**
 * The grouping {@code sql} (cycle 11, ADR-0079, decision 10 option A): the same books as {@link BookGrouping}, with
 * the counting and joining done in PostgreSQL temporary tables on a connection of its own, so the heap holds no
 * per-row or per-unit counts. Java still makes every normalized value and every key (the same calls as
 * {@link BookGrouping}), so the text rules live in one place.
 * <ol>
 * <li>First pass: every row is streamed with COPY into {@code grp_in}: a reading number {@code seq}, the step-① key,
 * the library, the item key, the raw values (blank strings as null, as {@link BookGrouping.Votes} skips them), the
 * date and the Kyobo-style ISBN.</li>
 * <li>Step ①: units numbered from 0 by their first row ({@code grp_unit}); the rows get their unit
 * ({@code grp_row}), and the counts per unit and value are kept ({@code grp_vote}, {@code grp_date},
 * {@code grp_unitlib}). {@code grp_label} gives every unit its group: the smallest unit number of the group, which is
 * the root {@link BookGrouping}'s union-find keeps (it always keeps the smaller root).</li>
 * <li>Joining ({@link #join}): edges between groups, then label propagation (each node takes the smallest label of
 * its neighbours, and the label of its label) until no row changes, at most {@code maxPasses} passes.</li>
 * <li>Most common values: rows counted per group and value in SQL; every value with the most rows comes back to Java,
 * which takes the smallest by {@link String#compareTo} (PostgreSQL's C collation orders by code point, which differs
 * from UTF-16 order for characters above U+FFFF against U+E000..U+FFFF). The date (most rows, then the earliest) and
 * the step-② ISBN (digits only) are picked in SQL, which orders them the same way as Java.</li>
 * <li>Steps ③ and ④ and the books stream one line per group back to Java (in group order) and write what Java
 * decides into a local file that COPY then loads: no group data stays on the heap either.</li>
 * <li>The second pass reads {@code (library, item key, book id)} in {@code seq} order through a cursor and checks
 * each row against it.</li>
 * </ol>
 * The tables live on disk when they pass {@code temp_buffers}; about two copies of the rows at most (17.7M rows:
 * several GB in PostgreSQL's temporary space).
 */
final class SqlGrouping implements BookGrouper {

	/** Passes a join may take before it fails [안]; the 2026-10 tests need under 20 for a chain of 3,000 units. */
	static final int MAX_PASSES = 1000;

	private static final int FLUSH_CHARS = 1 << 20;

	private static final int FETCH_SIZE = 10_000;

	/** Value kinds in {@code grp_vote}, and the extra kinds of a group line ({@link #forEachGroup}). */
	private static final int GROUP = 0, TITLE = 1, AUTHOR = 2, PUBLISHER = 3, CONTENT_TYPE = 4, ISBN = 5, DATE = 6,
			GROUP_ISBN = 7, LIBRARY = 8;

	private static final String ALL_GROUPS = "SELECT label FROM grp_label WHERE unit = label";

	private static final String GROUPS_WITHOUT_ISBN = ALL_GROUPS
			+ " AND NOT EXISTS (SELECT 1 FROM grp_gisbn i WHERE i.label = grp_label.label)";

	private static final List<String> TABLES = List.of("grp_in", "grp_unit", "grp_row", "grp_vote", "grp_date",
			"grp_unitlib", "grp_label", "grp_gisbn", "grp_edge", "grp_comp", "grp_attach", "grp_text", "grp_key",
			"grp_wide", "grp_book");

	private final Connection connection;

	private final CopyManager copyApi;

	private final int maxPasses;

	/** Passes each join took, in order (② ③ ④), for the tests and the log. */
	private final List<Integer> passes = new ArrayList<>();

	/** The first pass's COPY; null once {@link #groupByCodes} closed it. */
	private CopyIn rowsIn;

	private CopyText rows;

	private long seq;

	/** The second pass's cursor, opened by the first {@link #bookOf}. */
	private Statement cursorStatement;

	private ResultSet cursor;

	SqlGrouping(DataSource dataSource) {
		this(dataSource, MAX_PASSES);
	}

	/** @param maxPasses passes a join may take; more fails the import (a wrong group is never written) */
	SqlGrouping(DataSource dataSource, int maxPasses) {
		this.maxPasses = maxPasses;
		try {
			this.connection = dataSource.getConnection();
		}
		catch (SQLException ex) {
			throw failed("open a connection", ex);
		}
		try {
			connection.setAutoCommit(true);
			this.copyApi = connection.unwrap(PGConnection.class).getCopyAPI();
			dropTables();
			execute("""
					CREATE TEMPORARY TABLE grp_in (seq bigint NOT NULL, unit_key text NOT NULL, library_id bigint NOT NULL,
					  item_key text, title text, author text, publisher text, content_type text, published_on date,
					  isbn text)""");
			rowsIn = copyApi.copyIn("""
					COPY grp_in (seq, unit_key, library_id, item_key, title, author, publisher, content_type,
					  published_on, isbn) FROM STDIN""");
			rows = new CopyText(bytes -> rowsIn.writeToCopy(bytes, 0, bytes.length));
		}
		catch (SQLException | RuntimeException ex) {
			closeQuietly();
			throw ex instanceof SQLException sql ? failed("prepare the temporary tables", sql) : (RuntimeException) ex;
		}
	}

	/** Passes each join took so far (② ③ ④). */
	List<Integer> passes() {
		return List.copyOf(passes);
	}

	@Override
	public void add(Row row, ImportReport report) {
		if (rows == null) {
			throw new IllegalStateException("add after groupByCodes");
		}
		String isbn = BookGrouper.kyoboIsbn(row, report);
		try {
			rows.row(seq++, BookGrouper.unitKey(row), row.library().serviceId(), row.itemKey(), counted(row.title()),
					counted(row.authorRaw()), counted(row.publisher()), counted(row.contentType()), row.publishedOn(),
					counted(isbn));
		}
		catch (SQLException | IOException ex) {
			throw failed("copy a row", ex);
		}
	}

	/** A value as {@link BookGrouping.Votes} counts it: null and blank strings are not counted. */
	private static String counted(String value) {
		return value == null || value.isBlank() ? null : value;
	}

	/** Steps ① and ②. */
	@Override
	public void groupByCodes(ImportReport report) {
		try {
			rows.flush();
			long started = System.nanoTime();
			rowsIn.endCopy();
			rows.nanos += System.nanoTime() - started;
			rowsIn = null;
			// the first pass's COPY time, inside "read"
			report.steps.put("rows_copy", Duration.ofNanos(rows.nanos));
			rows = null;

			execute("ANALYZE grp_in");
			execute("""
					CREATE TEMPORARY TABLE grp_unit AS
					SELECT unit_key, (row_number() OVER (ORDER BY min(seq)) - 1)::int AS unit FROM grp_in GROUP BY unit_key""");
			execute("ANALYZE grp_unit");
			execute("""
					CREATE TEMPORARY TABLE grp_row AS
					SELECT i.seq, u.unit, i.library_id, i.item_key, i.title, i.author, i.publisher, i.content_type,
					  i.published_on, i.isbn
					FROM grp_in i JOIN grp_unit u USING (unit_key)""");
			report.booksAfterPlatformCode = count("SELECT count(*) FROM grp_unit");
			execute("CREATE TEMPORARY TABLE grp_label AS SELECT unit, unit AS label FROM grp_unit");
			execute("DROP TABLE grp_in, grp_unit");
			execute("ALTER TABLE grp_label ADD PRIMARY KEY (unit)");
			execute("CREATE INDEX ON grp_label (label)");
			execute("ANALYZE grp_row");
			execute("ANALYZE grp_label");
			execute("""
					CREATE TEMPORARY TABLE grp_vote AS
					SELECT r.unit, v.kind, v.value, count(*) AS n
					FROM grp_row r
					  CROSS JOIN LATERAL (VALUES (%d, r.title), (%d, r.author), (%d, r.publisher), (%d, r.content_type),
					    (%d, r.isbn)) AS v (kind, value)
					WHERE v.value IS NOT NULL
					GROUP BY r.unit, v.kind, v.value""".formatted(TITLE, AUTHOR, PUBLISHER, CONTENT_TYPE, ISBN));
			execute("""
					CREATE TEMPORARY TABLE grp_date AS
					SELECT unit, published_on AS d, count(*) AS n FROM grp_row WHERE published_on IS NOT NULL
					GROUP BY unit, published_on""");
			execute("CREATE TEMPORARY TABLE grp_unitlib AS SELECT DISTINCT unit, library_id FROM grp_row");
			execute("ANALYZE grp_vote");
			execute("ANALYZE grp_date");
			execute("ANALYZE grp_unitlib");

			// ② every ISBN of a unit joins it to the first unit with that ISBN
			join("""
					WITH iv AS (SELECT unit, value FROM grp_vote WHERE kind = %d),
					  f AS (SELECT value, min(unit) AS first FROM iv GROUP BY value)
					SELECT iv.unit, f.first FROM iv JOIN f USING (value) WHERE iv.unit <> f.first""".formatted(ISBN));
			// the ISBN of a group: the one most of its rows computed, ties to the smallest (digits: C order = Java's)
			execute("""
					CREATE TEMPORARY TABLE grp_gisbn AS
					SELECT DISTINCT ON (l.label) l.label, v.value AS isbn
					FROM grp_vote v JOIN grp_label l USING (unit)
					WHERE v.kind = %d
					GROUP BY l.label, v.value
					ORDER BY l.label, sum(v.n) DESC, v.value COLLATE "C\"""".formatted(ISBN));
			execute("ALTER TABLE grp_gisbn ADD PRIMARY KEY (label)");
			execute("ANALYZE grp_gisbn");
			report.booksAfterKyoboIsbn = groupCount();
		}
		catch (SQLException | IOException ex) {
			throw failed("group by codes", ex);
		}
	}

	@Override
	public Set<String> bibliographyKeys() {
		Set<String> keys = new HashSet<>();
		try {
			forEachGroup(GROUPS_WITHOUT_ISBN, new int[] { TITLE, AUTHOR, PUBLISHER }, false, false, group -> {
				String key = bibliographyKey(group);
				if (key != null) {
					keys.add(key);
				}
			});
		}
		catch (SQLException | IOException ex) {
			throw failed("read the bibliography keys", ex);
		}
		return keys;
	}

	/** {@code BookGrouping.keyOf}: from the group's most common raw title, author and publisher. */
	private static String bibliographyKey(Group group) {
		String publisher = CatalogText.publisherNorm(group.values[PUBLISHER]);
		String author = BookGrouper.firstAuthor(Contributors.parse(group.values[AUTHOR]));
		return BookGrouper.key(CatalogText.mainTitleNorm(group.values[TITLE]), author, publisher);
	}

	/**
	 * Step ③: the groups without an ISBN that get one are written to {@code grp_attach}; then every group with that
	 * ISBN (from ② or ③) is joined. Two groups never share an ISBN after ②, so the join is a star per ISBN.
	 */
	@Override
	public void attachBibliography(Map<String, String> isbnByKey, ImportReport report) {
		try {
			execute("CREATE TEMPORARY TABLE grp_attach (label int NOT NULL, isbn text NOT NULL)");
			long[] attached = { 0 };
			copyThroughFile("COPY grp_attach (label, isbn) FROM STDIN",
					out -> forEachGroup(GROUPS_WITHOUT_ISBN, new int[] { TITLE, AUTHOR, PUBLISHER }, false, false,
							group -> {
								String key = bibliographyKey(group);
								String isbn = key == null ? null : isbnByKey.get(key);
								if (isbn != null) {
									attached[0]++;
									out.row(group.label, isbn);
								}
							}));
			report.nationalBibliographyIsbnAttached += attached[0];
			execute("INSERT INTO grp_gisbn (label, isbn) SELECT label, isbn FROM grp_attach");
			execute("ANALYZE grp_gisbn");
			join("""
					WITH f AS (SELECT isbn, min(label) AS first FROM grp_gisbn GROUP BY isbn)
					SELECT g.label, f.first FROM grp_gisbn g JOIN f USING (isbn) WHERE g.label <> f.first""");
			execute("DROP TABLE grp_attach");
			report.booksAfterNationalBibliography = groupCount();
		}
		catch (SQLException | IOException ex) {
			throw failed("attach the national bibliography", ex);
		}
	}

	/**
	 * Step ④: every group's same-text key, computed in Java from the groups as they are when the step starts, goes to
	 * {@code grp_text}; groups with one key are joined to the first. The counts follow {@code BookGrouping}.
	 */
	@Override
	public void mergeSameText(ImportReport report) {
		try {
			execute("CREATE TEMPORARY TABLE grp_text (label int NOT NULL, key text COLLATE \"C\" NOT NULL, h int NOT NULL, d date)");
			copyThroughFile("COPY grp_text (label, key, h, d) FROM STDIN",
					out -> forEachGroup(ALL_GROUPS, new int[] { TITLE, AUTHOR, PUBLISHER }, true, false, group -> {
						String key = BookGrouper.key(CatalogText.titleNorm(group.values[TITLE]),
								BookGrouper.firstAuthor(Contributors.parse(group.values[AUTHOR])),
								CatalogText.publisherNorm(group.values[PUBLISHER]));
						if (key != null) {
							out.row(group.label, key, key.hashCode(), group.date);
						}
					}));
			execute("ANALYZE grp_text");
			// groups whose key an earlier group had
			report.sameTextMerged += count("SELECT count(*) - count(DISTINCT key) FROM grp_text");
			execute("""
					CREATE TEMPORARY TABLE grp_key AS
					SELECT key, min(label) AS first, min(label) FILTER (WHERE d IS NOT NULL) AS first_dated,
					  min(d) AS lo, max(d) AS hi, min(h) AS h
					FROM grp_text GROUP BY key""");
			wideDates(report);
			join("SELECT t.label, k.first FROM grp_text t JOIN grp_key k USING (key) WHERE t.label <> k.first");
			execute("DROP TABLE grp_text, grp_key");
			report.booksAfterSameText = groupCount();
		}
		catch (SQLException | IOException ex) {
			throw failed("merge the same text", ex);
		}
	}

	/** One merged key whose groups' dates are 2+ years apart (BookGrouping's {@code dateRange} entry). */
	record WideKey(int hash, int firstDated, String line) {
	}

	/**
	 * Merged books whose groups' dates are 2+ years apart, counted as {@code BookGrouping} does, with the samples in
	 * the order its {@code HashMap} gives them ({@link #hashMapOrder}).
	 */
	private void wideDates(ImportReport report) throws SQLException, IOException {
		long datedKeys = count("SELECT count(*) FROM grp_key WHERE lo IS NOT NULL");
		if (datedKeys == 0) {
			return;
		}
		// the map's last computeIfAbsent: a new key when the last dated group is its key's first dated group
		long lastIsNew = count("""
				SELECT count(*) FROM grp_key
				WHERE first_dated = (SELECT max(label) FROM grp_text WHERE d IS NOT NULL)""");
		long sizeAtLastCall = datedKeys - lastIsNew;
		// a superset in SQL (2 years are 730 or 731 days), the exact rule in Java
		execute("CREATE TEMPORARY TABLE grp_wide AS SELECT first AS label, first_dated, h, lo, hi FROM grp_key WHERE hi - lo > 700");
		record Candidate(int first, int firstDated, int hash, LocalDate lo, LocalDate hi) {
		}
		List<Candidate> candidates = new ArrayList<>();
		try (Statement statement = connection.createStatement();
				ResultSet rs = statement.executeQuery("SELECT label, first_dated, h, lo, hi FROM grp_wide")) {
			while (rs.next()) {
				LocalDate lo = rs.getObject(4, LocalDate.class);
				LocalDate hi = rs.getObject(5, LocalDate.class);
				if (lo.plusYears(2).isBefore(hi)) {
					candidates.add(new Candidate(rs.getInt(1), rs.getInt(2), rs.getInt(3), lo, hi));
				}
			}
		}
		if (!candidates.isEmpty()) {
			Map<Integer, String> sampleOf = new HashMap<>();
			forEachGroup("SELECT label FROM grp_wide", new int[] { TITLE, AUTHOR, PUBLISHER }, false, false,
					group -> sampleOf.put(group.label, group.values[TITLE] + " / " + group.values[AUTHOR] + " / "
							+ group.values[PUBLISHER]));
			List<WideKey> wide = candidates.stream()
				.map(c -> new WideKey(c.hash(), c.firstDated(), c.lo() + "~" + c.hi() + " " + sampleOf.get(c.first())))
				.toList();
			for (WideKey key : hashMapOrder(wide, sizeAtLastCall)) {
				report.sameTextWideDates++;
				if (report.sameTextWideDateSamples.size() < 40) {
					report.sameTextWideDateSamples.add(key.line());
				}
			}
		}
		execute("DROP TABLE grp_wide");
	}

	/**
	 * The order a default {@link HashMap} filled by {@code computeIfAbsent} (one call per dated group, in group order)
	 * gives its keys: by bucket, and inside a bucket the newest first ({@code computeIfAbsent} puts a new node at the
	 * head; a resize keeps the order). The table doubles at the start of a call when the size passed 3/4 of it, so the
	 * size at the last call sets it. Not covered: a bucket of 8+ keys, which the map turns into a tree (or, below 64
	 * buckets, resizes for); that changes the order only of the wide keys inside that bucket.
	 * @param sizeAtLastCall keys in the map when the last call started
	 */
	static List<WideKey> hashMapOrder(List<WideKey> keys, long sizeAtLastCall) {
		int capacity = 16;
		while (sizeAtLastCall > capacity / 4 * 3) {
			capacity <<= 1;
		}
		int mask = capacity - 1;
		return keys.stream()
			.sorted(Comparator.<WideKey>comparingInt(k -> (k.hash() ^ (k.hash() >>> 16)) & mask)
				.thenComparing(Comparator.comparingInt(WideKey::firstDated).reversed()))
			.toList();
	}

	/** Gives every book to {@code sink} in group order and keeps each group's id in {@code grp_book}. */
	@Override
	public void forEachBook(ToLongFunction<Book> sink) {
		try {
			execute("CREATE TEMPORARY TABLE grp_book (label int NOT NULL, book_id bigint NOT NULL)");
			copyThroughFile("COPY grp_book (label, book_id) FROM STDIN", out -> forEachGroup(ALL_GROUPS,
					new int[] { TITLE, AUTHOR, PUBLISHER, CONTENT_TYPE }, true, true, group -> {
						String title = group.values[TITLE];
						String authorRaw = group.values[AUTHOR];
						String publisher = group.values[PUBLISHER];
						Book book = new Book(title, authorRaw, publisher, CatalogText.titleNorm(title),
								CatalogText.mainTitleNorm(title), CatalogText.publisherNorm(publisher), group.date,
								group.isbn, group.values[CONTENT_TYPE], Contributors.parse(authorRaw),
								Arrays.stream(group.libraries, 0, group.libraryCount).sorted().distinct().toArray());
						out.row(group.label, sink.applyAsLong(book));
					}));
			execute("ALTER TABLE grp_book ADD PRIMARY KEY (label)");
			execute("ANALYZE grp_book");
		}
		catch (SQLException | IOException ex) {
			throw failed("give the books", ex);
		}
	}

	/**
	 * Second pass: the rows come in the first pass's order, so the cursor over the rows by {@code seq} gives each
	 * row's book; its (library, item key) is checked. One second pass per grouping.
	 */
	@Override
	public long bookOf(Row row) {
		try {
			if (cursor == null) {
				connection.setAutoCommit(false);
				cursorStatement = connection.createStatement();
				cursorStatement.setFetchSize(FETCH_SIZE);
				cursor = cursorStatement.executeQuery("""
						SELECT r.library_id, r.item_key, b.book_id
						FROM grp_row r JOIN grp_label l USING (unit) JOIN grp_book b ON b.label = l.label
						ORDER BY r.seq""");
			}
			if (!cursor.next()) {
				throw new IllegalStateException("Row not seen in the first pass: " + BookGrouper.unitKey(row));
			}
			if (cursor.getLong(1) != row.library().serviceId() || !Objects.equals(cursor.getString(2), row.itemKey())) {
				throw new IllegalStateException("The second pass differs from the first at row " + cursor.getRow()
						+ ": expected library " + cursor.getLong(1) + " item " + cursor.getString(2) + ", got library "
						+ row.library().serviceId() + " item " + row.itemKey());
			}
			return cursor.getLong(3);
		}
		catch (SQLException ex) {
			throw failed("find a row's book", ex);
		}
	}

	/** Drops the temporary tables and gives the connection back. */
	@Override
	public void close() {
		closeQuietly();
	}

	private void closeQuietly() {
		try {
			if (rowsIn != null && rowsIn.isActive()) {
				rowsIn.cancelCopy();
			}
		}
		catch (SQLException ignored) {
			// the connection is closed below anyway
		}
		try (connection) {
			if (cursor != null) {
				cursor.close();
				cursorStatement.close();
			}
			if (!connection.getAutoCommit()) {
				connection.rollback();
				connection.setAutoCommit(true);
			}
			dropTables();
		}
		catch (SQLException ex) {
			throw failed("drop the temporary tables", ex);
		}
	}

	private void dropTables() throws SQLException {
		execute("DROP TABLE IF EXISTS " + String.join(", ", TABLES));
	}

	/**
	 * Joins groups: {@code edgesSql} gives pairs of group labels (any direction). Label propagation over them: each
	 * node takes the smallest label among its neighbours, then the label of its label, until a pass changes nothing.
	 * The label of a component ends as its smallest node, the root {@link BookGrouping}'s union-find keeps. A group's
	 * ISBN is then the one of its smallest former group that had one ("its ISBN comes first").
	 */
	private void join(String edgesSql) throws SQLException {
		execute("CREATE TEMPORARY TABLE grp_edge AS SELECT e.a, e.b FROM (" + edgesSql + ") AS e (a, b)");
		execute("INSERT INTO grp_edge SELECT b, a FROM grp_edge");
		execute("CREATE TEMPORARY TABLE grp_comp AS SELECT DISTINCT a AS node, a AS comp FROM grp_edge");
		execute("ALTER TABLE grp_comp ADD PRIMARY KEY (node)");
		execute("ANALYZE grp_edge");
		execute("ANALYZE grp_comp");
		for (int pass = 1;; pass++) {
			if (pass > maxPasses) {
				throw new IllegalStateException(
						"Grouping did not settle in " + maxPasses + " passes; refusing to continue with partial groups");
			}
			long changed = update("""
					UPDATE grp_comp c SET comp = s.m
					FROM (SELECT e.a AS node, min(n.comp) AS m FROM grp_edge e JOIN grp_comp n ON n.node = e.b GROUP BY e.a) s
					WHERE c.node = s.node AND s.m < c.comp""");
			changed += update("""
					UPDATE grp_comp c SET comp = p.comp FROM grp_comp p WHERE p.node = c.comp AND p.comp < c.comp""");
			if (changed == 0) {
				passes.add(pass);
				break;
			}
		}
		execute("UPDATE grp_label l SET label = c.comp FROM grp_comp c WHERE l.label = c.node AND c.comp < c.node");
		if (exists("grp_gisbn")) {
			execute("""
					CREATE TEMPORARY TABLE grp_gisbn_next AS
					SELECT DISTINCT ON (coalesce(c.comp, g.label)) coalesce(c.comp, g.label) AS label, g.isbn
					FROM grp_gisbn g LEFT JOIN grp_comp c ON c.node = g.label
					ORDER BY coalesce(c.comp, g.label), g.label""");
			execute("DROP TABLE grp_gisbn");
			execute("ALTER TABLE grp_gisbn_next RENAME TO grp_gisbn");
			execute("ALTER TABLE grp_gisbn ADD PRIMARY KEY (label)");
			execute("ANALYZE grp_gisbn");
		}
		execute("DROP TABLE grp_edge, grp_comp");
		execute("ANALYZE grp_label");
	}

	/** One group line of {@link #forEachGroup}: its most common values (index = kind), date, ISBN and libraries. */
	private static final class Group {

		int label;

		final String[] values = new String[ISBN + 1];

		LocalDate date;

		String isbn;

		long[] libraries = new long[8];

		int libraryCount;

		void reset(int label) {
			this.label = label;
			Arrays.fill(values, null);
			date = null;
			isbn = null;
			libraryCount = 0;
		}

	}

	@FunctionalInterface
	private interface GroupAction {

		void accept(Group group) throws SQLException, IOException;

	}

	/**
	 * Streams the groups of {@code groupsSql} (a query giving {@code label}) in group order, one {@link Group} each:
	 * the most common value of each kind (every tied value comes back; the smallest by {@link String#compareTo} wins),
	 * and when asked the date, the ISBN and the libraries.
	 */
	private void forEachGroup(String groupsSql, int[] kinds, boolean withDate, boolean withIsbnAndLibraries,
			GroupAction action) throws SQLException, IOException {
		StringBuilder sql = new StringBuilder("""
				WITH g AS (%s),
				  s AS (SELECT l.label, v.kind, v.value, sum(v.n) AS n
				    FROM grp_vote v JOIN grp_label l USING (unit) JOIN g USING (label)
				    WHERE v.kind IN (%s) GROUP BY l.label, v.kind, v.value)
				SELECT label, %d AS kind, NULL::text AS value FROM g
				UNION ALL
				SELECT label, kind, value
				FROM (SELECT label, kind, value, rank() OVER (PARTITION BY label, kind ORDER BY n DESC) AS rk FROM s) t
				WHERE rk = 1
				""".formatted(groupsSql,
				IntStream.of(kinds).mapToObj(Integer::toString).collect(Collectors.joining(", ")), GROUP));
		if (withDate) {
			// the date seen most often, the earlier on a tie (PublishedDates.representative)
			sql.append("""
					UNION ALL
					SELECT label, %d, to_char(d, 'YYYY-MM-DD')
					FROM (SELECT DISTINCT ON (l.label) l.label, d.d
					  FROM grp_date d JOIN grp_label l USING (unit) JOIN g USING (label)
					  GROUP BY l.label, d.d ORDER BY l.label, sum(d.n) DESC, d.d) x
					""".formatted(DATE));
		}
		if (withIsbnAndLibraries) {
			sql.append("""
					UNION ALL
					SELECT i.label, %d, i.isbn FROM grp_gisbn i JOIN g USING (label)
					UNION ALL
					SELECT DISTINCT l.label, %d, u.library_id::text
					FROM grp_unitlib u JOIN grp_label l USING (unit) JOIN g USING (label)
					""".formatted(GROUP_ISBN, LIBRARY));
		}
		sql.append("ORDER BY 1, 2");

		Group group = new Group();
		boolean[] open = { false };
		query(sql.toString(), rs -> {
			int label = rs.getInt(1);
			int kind = rs.getInt(2);
			String value = rs.getString(3);
			switch (kind) {
				case GROUP -> {
					if (open[0]) {
						action.accept(group);
					}
					group.reset(label);
					open[0] = true;
				}
				case DATE -> group.date = LocalDate.parse(value);
				case GROUP_ISBN -> group.isbn = value;
				case LIBRARY -> {
					if (group.libraryCount == group.libraries.length) {
						group.libraries = Arrays.copyOf(group.libraries, group.libraryCount * 2);
					}
					group.libraries[group.libraryCount++] = Long.parseLong(value);
				}
				default -> {
					if (group.values[kind] == null || value.compareTo(group.values[kind]) < 0) {
						group.values[kind] = value;
					}
				}
			}
			if (group.label != label) {
				throw new IllegalStateException("Group line out of order: " + label + " in group " + group.label);
			}
		});
		if (open[0]) {
			action.accept(group);
		}
	}

	@FunctionalInterface
	private interface RowReader {

		void read(ResultSet rs) throws SQLException, IOException;

	}

	/** Reads a query through a cursor (a cursor needs a transaction; without one the driver reads every row first). */
	private void query(String sql, RowReader reader) throws SQLException, IOException {
		connection.setAutoCommit(false);
		try (Statement statement = connection.createStatement()) {
			statement.setFetchSize(FETCH_SIZE);
			try (ResultSet rs = statement.executeQuery(sql)) {
				while (rs.next()) {
					reader.read(rs);
				}
			}
			connection.commit();
		}
		catch (SQLException | IOException | RuntimeException ex) {
			connection.rollback();
			throw ex;
		}
		finally {
			connection.setAutoCommit(true);
		}
	}

	@FunctionalInterface
	private interface CopyFill {

		void fill(CopyText out) throws SQLException, IOException;

	}

	/**
	 * Writes COPY lines into a local file while a query streams on the connection, then loads the file (COPY cannot
	 * run on a connection while a cursor reads on it).
	 */
	private void copyThroughFile(String copySql, CopyFill fill) throws SQLException, IOException {
		Path file = Files.createTempFile("sql-grouping", ".copy");
		try {
			try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(file), 1 << 16)) {
				CopyText text = new CopyText(out::write);
				fill.fill(text);
				text.flush();
			}
			try (InputStream in = new BufferedInputStream(Files.newInputStream(file), 1 << 16)) {
				copyApi.copyIn(copySql, in);
			}
		}
		finally {
			Files.deleteIfExists(file);
		}
	}

	private long groupCount() throws SQLException {
		return count("SELECT count(*) FROM grp_label WHERE unit = label");
	}

	private boolean exists(String table) throws SQLException {
		return count("SELECT count(*) FROM pg_class WHERE relname = '" + table
				+ "' AND relnamespace = pg_my_temp_schema()") > 0;
	}

	private void execute(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			statement.execute(sql);
		}
	}

	private long update(String sql) throws SQLException {
		try (Statement statement = connection.createStatement()) {
			return statement.executeLargeUpdate(sql);
		}
	}

	private long count(String sql) throws SQLException {
		try (Statement statement = connection.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
			rs.next();
			return rs.getLong(1);
		}
	}

	private static IllegalStateException failed(String what, Exception cause) {
		return new IllegalStateException("sql grouping: could not " + what + ": " + cause.getMessage(), cause);
	}

	/** COPY text format: tab separated, {@code \N} for null, backslash escapes; handed on in chunks of UTF-8. */
	private static final class CopyText {

		@FunctionalInterface
		interface Chunks {

			void write(byte[] utf8) throws SQLException, IOException;

		}

		private final StringBuilder buffer = new StringBuilder(FLUSH_CHARS + 4096);

		private final Chunks out;

		/** Time spent handing lines on. */
		long nanos;

		CopyText(Chunks out) {
			this.out = out;
		}

		void row(Object... values) throws SQLException, IOException {
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
			if (buffer.length() >= FLUSH_CHARS) {
				flush();
			}
		}

		void flush() throws SQLException, IOException {
			long started = System.nanoTime();
			out.write(buffer.toString().getBytes(StandardCharsets.UTF_8));
			buffer.setLength(0);
			nanos += System.nanoTime() - started;
		}

	}

}
