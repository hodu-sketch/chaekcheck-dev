package com.chaekcheck.catalog.internal.texts;

import java.sql.Array;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.chaekcheck.catalog.BookProfiles.Profile;
import com.chaekcheck.catalog.BookTextsTransfer;
import com.chaekcheck.catalog.SearchText;

/** {@link BookTextsTransfer} with {@code JdbcClient} and batch inserts, each call in one transaction. */
@Component
class JdbcBookTextsTransfer implements BookTextsTransfer {

	private static final Logger log = LoggerFactory.getLogger(JdbcBookTextsTransfer.class);

	private static final int BATCH = 500;

	private final JdbcClient jdbc;

	private final JdbcTemplate template;

	JdbcBookTextsTransfer(JdbcClient jdbc, JdbcTemplate template) {
		this.jdbc = jdbc;
		this.template = template;
	}

	@Override
	@Transactional
	public int upsertSummaries(Collection<Summary> summaries) {
		template.batchUpdate("""
				INSERT INTO catalog.book_summary (book_id, summary, made_on) VALUES (?, ?, ?)
				ON CONFLICT (book_id) DO UPDATE SET summary = EXCLUDED.summary, made_on = EXCLUDED.made_on""",
				summaries, BATCH, (statement, summary) -> {
					statement.setLong(1, summary.bookId());
					statement.setString(2, summary.text());
					statement.setDate(3, Date.valueOf(summary.madeOn()));
				});
		return summaries.size();
	}

	@Override
	@Transactional
	public int upsertCovers(Collection<Cover> covers) {
		template.batchUpdate("""
				INSERT INTO catalog.book_cover (book_id, file_key) VALUES (?, ?)
				ON CONFLICT (book_id) DO UPDATE SET file_key = EXCLUDED.file_key""", covers, BATCH,
				(statement, cover) -> {
					statement.setLong(1, cover.bookId());
					statement.setString(2, cover.fileKey());
				});
		return covers.size();
	}

	@Override
	@Transactional
	public int replaceTags(long bookId, Collection<String> codes, LocalDate madeOn) {
		Set<String> wanted = new LinkedHashSet<>(codes);
		List<String> known = wanted.isEmpty() ? List.of()
				: jdbc.sql("SELECT code FROM catalog.tag_dictionary WHERE code = ANY(CAST(:codes AS text[]))")
					.param("codes", wanted.toArray(String[]::new))
					.query(String.class)
					.list();
		jdbc.sql("DELETE FROM catalog.book_tag WHERE book_id = ?").param(bookId).update();
		for (String code : known) {
			jdbc.sql("INSERT INTO catalog.book_tag (book_id, tag_code, made_on) VALUES (?, ?, ?)")
				.params(bookId, code, madeOn)
				.update();
		}
		return wanted.size() - known.size();
	}

	@Override
	@Transactional
	public int replaceTagDictionary(Collection<DictionaryRow> rows) {
		Set<String> codes = new LinkedHashSet<>();
		for (DictionaryRow row : rows) {
			codes.add(row.code());
			jdbc.sql("""
					INSERT INTO catalog.tag_dictionary (code, axis, name, thema_code, active)
					VALUES (:code, :axis, :name, :thema, true)
					ON CONFLICT (code) DO UPDATE SET axis = EXCLUDED.axis, name = EXCLUDED.name,
					  thema_code = EXCLUDED.thema_code, active = true""")
				.param("code", row.code())
				.param("axis", row.axis())
				.param("name", row.name())
				.param("thema", row.themaCode())
				.update();
		}
		return jdbc
			.sql("UPDATE catalog.tag_dictionary SET active = false WHERE active AND NOT code = ANY(CAST(:codes AS text[]))")
			.param("codes", codes.toArray(String[]::new))
			.update();
	}

	@Override
	@Transactional
	public int replaceProfiles(Collection<BookProfile> profiles) {
		if (profiles.isEmpty()) {
			throw new IllegalArgumentException("no profiles: the profiles are kept as they are");
		}
		Set<Long> seen = new HashSet<>();
		for (BookProfile row : profiles) {
			if (!seen.add(row.bookId())) {
				throw new IllegalArgumentException("book " + row.bookId() + " comes twice");
			}
		}
		int deleted = jdbc.sql("DELETE FROM catalog.book_profile").update();
		template.batchUpdate("""
				INSERT INTO catalog.book_profile
				  (book_id, field, subfield, fiction, genres, country, audience, level, forms)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""", profiles, BATCH, (statement, row) -> {
			Profile profile = row.profile();
			statement.setLong(1, row.bookId());
			statement.setString(2, profile.field());
			statement.setString(3, profile.subfield());
			if (profile.fiction() == null) {
				statement.setNull(4, Types.BOOLEAN);
			}
			else {
				statement.setBoolean(4, profile.fiction());
			}
			statement.setArray(5, statement.getConnection().createArrayOf("text", profile.genres().toArray()));
			statement.setString(6, profile.country());
			statement.setString(7, profile.audience());
			statement.setString(8, profile.level());
			statement.setArray(9, statement.getConnection().createArrayOf("text", profile.forms().toArray()));
		});
		log.info("book profiles replaced: deleted {}, stored {}", deleted, profiles.size());
		return profiles.size();
	}

	@Override
	@Transactional
	public SearchResult replaceSearchBooks(List<Long> bookIds, List<Line> lines, List<Written> written) {
		if (bookIds.isEmpty()) {
			throw new IllegalArgumentException("no books: the search books are kept as they are");
		}
		Set<Long> given = new HashSet<>();
		for (Long id : bookIds) {
			if (!given.add(id)) {
				throw new IllegalArgumentException("book " + id + " comes twice");
			}
		}
		Map<Long, Line> lineByBook = new HashMap<>();
		for (Line line : lines) {
			if (lineByBook.put(line.bookId(), line) != null) {
				throw new IllegalArgumentException("two lines for book " + line.bookId());
			}
		}
		Map<Long, Written> writtenByBook = new HashMap<>();
		for (Written row : written) {
			if (writtenByBook.put(row.bookId(), row) != null) {
				throw new IllegalArgumentException("two written rows for book " + row.bookId());
			}
		}
		if (written.isEmpty()) {
			Integer stored = jdbc.sql("SELECT count(written_on) FROM catalog.book_search").query(Integer.class).single();
			if (stored != null && stored > 0) {
				throw new IllegalStateException("The new file has no summary or search terms but " + stored
						+ " books have them now; refused so that none is lost, the search books were kept");
			}
		}
		if (lines.isEmpty()) {
			Integer stored = jdbc.sql("SELECT count(line) FROM catalog.book_search").query(Integer.class).single();
			if (stored != null && stored > 0) {
				throw new IllegalStateException("The new file has no search line but " + stored
						+ " books have one now; refused so that no line is lost, the search books were kept");
			}
		}
		List<Book> books = new ArrayList<>(bookIds.size());
		jdbc.sql("""
				SELECT id, title, author_display, library_ids FROM catalog.book WHERE id = ANY(CAST(:ids AS bigint[]))
				ORDER BY id""").param("ids", bookIds.toArray(Long[]::new)).query(rs -> {
			books.add(new Book(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getArray(4)));
		});
		Set<Long> stored = new HashSet<>();
		books.forEach(book -> stored.add(book.id()));
		int linesNotListed = (int) lineByBook.keySet().stream().filter(id -> !stored.contains(id)).count();
		// cycle 29 (ADR-0174): a new table swapped in at the end, not DELETE + INSERT on the table in use, so no VACUUM FULL
		// is needed afterwards and the AI search reads the old rows until the swap
		jdbc.sql("DROP TABLE IF EXISTS " + NEXT).update();
		jdbc.sql("CREATE TABLE " + NEXT + " (LIKE " + LIVE + " INCLUDING ALL EXCLUDING INDEXES)").update();
		int[] withLine = { 0 };
		int[] withWritten = { 0 };
		template.batchUpdate("""
				INSERT INTO catalog.book_search_next
				  (book_id, line, line_source, line_made_on, search_text, library_ids, summary, terms, written_on,
				   keyword_text)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", books, BATCH, (statement, book) -> {
			Line line = lineByBook.get(book.id());
			Written words = writtenByBook.get(book.id());
			statement.setLong(1, book.id());
			if (line == null) {
				statement.setNull(2, Types.VARCHAR);
				statement.setNull(3, Types.VARCHAR);
				statement.setNull(4, Types.DATE);
			}
			else {
				withLine[0]++;
				statement.setString(2, line.text());
				statement.setString(3, line.source().code());
				statement.setDate(4, Date.valueOf(line.madeOn()));
			}
			statement.setString(5, SearchText.ofBook(book.title(), book.author(), line == null ? null : line.text()));
			// catalog.book keeps bigint ids; book_search keeps 4-byte ones (aisearch-004, ADR-0160)
			statement.setArray(6, statement.getConnection().createArrayOf("integer", book.libraryIds()));
			if (words == null) {
				statement.setNull(7, Types.VARCHAR);
				statement.setNull(8, Types.VARCHAR);
				statement.setNull(9, Types.DATE);
			}
			else {
				withWritten[0]++;
				statement.setString(7, words.summary());
				statement.setString(8, words.terms());
				statement.setDate(9, Date.valueOf(words.madeOn()));
			}
			statement.setString(10, keywordText(book, line, words));
		});
		SearchResult result = new SearchResult(books.size(), withLine[0], bookIds.size() - books.size(),
				linesNotListed);
		int deleted = swapIn();
		log.info("search books replaced: deleted {}, stored {}, with line {}, with summary or terms {}, not in catalog {}, "
				+ "lines not listed {}", deleted, result.stored(), result.withLine(), withWritten[0], result.notInCatalog(),
				result.linesNotListed());
		if (linesNotListed > 0) {
			log.warn("{} search lines are for books not searched now: was the file made from another database?",
					linesNotListed);
		}
		return result;
	}

	private static final String LIVE = "catalog.book_search";

	private static final String NEXT = "catalog.book_search_next";

	/**
	 * Gives {@link #NEXT} the indexes, keys and statistics settings {@link #LIVE} has now (read from the catalog, so an
	 * index a later changeset adds comes along; the BM25 index too), analyses it and swaps it in: the old table dropped
	 * under a lock that waits for readers at most 10 s [안], the new one and its indexes renamed. Runs in the caller's
	 * transaction, whose commit ends the lock.
	 * @return the rows the old table had
	 */
	private int swapIn() {
		record Index(String name, String definition, String constraint) {
		}
		List<Index> indexes = jdbc.sql("""
				SELECT c.relname, pg_get_indexdef(i.indexrelid),
				  (SELECT pg_get_constraintdef(con.oid) FROM pg_constraint con
				   WHERE con.conindid = i.indexrelid AND con.conrelid = i.indrelid AND con.contype IN ('p', 'u'))
				FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid
				WHERE i.indrelid = CAST(:live AS regclass) ORDER BY c.relname""")
			.param("live", LIVE)
			.query((rs, n) -> new Index(rs.getString(1), rs.getString(2), rs.getString(3)))
			.list();
		for (Index index : indexes) {
			if (index.constraint() != null) {
				jdbc.sql("ALTER TABLE " + NEXT + " ADD CONSTRAINT " + index.name() + "_next " + index.constraint())
					.update();
				continue;
			}
			String from = " INDEX " + index.name() + " ON " + LIVE + " ";
			if (!index.definition().contains(from)) {
				throw new IllegalStateException("unexpected index definition, the search books were kept: "
						+ index.definition());
			}
			jdbc.sql(index.definition().replace(from, " INDEX " + index.name() + "_next ON " + NEXT + " ")).update();
		}
		// the per-column statistics targets (catalog/006 sets 0 on the long text columns and the BM25 index's)
		jdbc.sql("""
				SELECT a.attname, a.attstattarget FROM pg_attribute a
				WHERE a.attrelid = CAST(:live AS regclass) AND a.attnum > 0 AND NOT a.attisdropped
				  AND a.attstattarget IS NOT NULL AND a.attstattarget >= 0""")
			.param("live", LIVE)
			.query((rs, n) -> "ALTER TABLE " + NEXT + " ALTER COLUMN " + rs.getString(1) + " SET STATISTICS "
					+ rs.getInt(2))
			.list()
			.forEach(sql -> jdbc.sql(sql).update());
		for (Index index : indexes) {
			jdbc.sql("""
					SELECT a.attnum, a.attstattarget FROM pg_attribute a
					WHERE a.attrelid = CAST(:index AS regclass) AND a.attstattarget IS NOT NULL AND a.attstattarget >= 0""")
				.param("index", "catalog." + index.name())
				.query((rs, n) -> "ALTER INDEX catalog." + index.name() + "_next ALTER COLUMN " + rs.getInt(1)
						+ " SET STATISTICS " + rs.getInt(2))
				.list()
				.forEach(sql -> jdbc.sql(sql).update());
		}
		jdbc.sql("ANALYZE " + NEXT).update();
		Integer old = jdbc.sql("SELECT count(*) FROM " + LIVE).query(Integer.class).single();
		jdbc.sql("SET LOCAL lock_timeout = '10s'").update();
		jdbc.sql("LOCK TABLE " + LIVE + " IN ACCESS EXCLUSIVE MODE").update();
		jdbc.sql("DROP TABLE " + LIVE).update();
		jdbc.sql("ALTER TABLE " + NEXT + " RENAME TO book_search").update();
		for (Index index : indexes) {
			jdbc.sql("ALTER INDEX catalog." + index.name() + "_next RENAME TO " + index.name()).update();
		}
		return old == null ? 0 : old;
	}

	/**
	 * The BM25 text (catalog/006 {@code keyword_text}): title, author, line and search terms with their spaces, which
	 * the Korean analyser needs ({@code search_text} has none), each part's runs of white space made one space.
	 */
	private static String keywordText(Book book, Line line, Written words) {
		StringBuilder text = new StringBuilder();
		for (String part : new String[] { book.title(), book.author(), line == null ? null : line.text(),
				words == null ? null : words.terms() }) {
			if (part != null && !part.isBlank()) {
				if (!text.isEmpty()) {
					text.append(' ');
				}
				text.append(String.join(" ", part.strip().split("\\s+")));
			}
		}
		return text.toString();
	}

	private record Book(long id, String title, String author, Integer[] libraryIds) {

		Book(long id, String title, String author, Array libraryIds) throws SQLException {
			this(id, title, author, integers(libraryIds));
		}

		private static Integer[] integers(Array array) throws SQLException {
			if (array == null) {
				return new Integer[0];
			}
			Object[] values = (Object[]) array.getArray();
			Integer[] ids = new Integer[values.length];
			for (int i = 0; i < values.length; i++) {
				ids[i] = Math.toIntExact(((Number) values[i]).longValue());
			}
			return ids;
		}

	}

}
