package com.chaekcheck.aisearch.internal.books;

import java.sql.Date;
import java.sql.Types;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.chaekcheck.aisearch.AiSearchBookTexts;
import com.chaekcheck.aisearch.internal.match.SearchText;

/** {@link AiSearchBookTexts} with {@code JdbcTemplate}: delete every row, then one batch insert, in one transaction. */
@Component
class JdbcAiSearchBookTexts implements AiSearchBookTexts {

	private static final Logger log = LoggerFactory.getLogger(JdbcAiSearchBookTexts.class);

	private static final String INSERT = """
			INSERT INTO aisearch.search_book
			  (book_id, title, author, line, line_source, line_made_on, search_text, library_ids)
			VALUES (?, ?, ?, ?, ?, ?, ?, ?)""";

	private final JdbcTemplate jdbc;

	JdbcAiSearchBookTexts(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	@Transactional
	public Result replace(List<Book> books, List<Line> lines) {
		if (books.isEmpty()) {
			throw new IllegalArgumentException("no books: the search books are kept as they are");
		}
		Set<Long> listed = new HashSet<>();
		for (Book book : books) {
			if (!listed.add(book.bookId())) {
				throw new IllegalArgumentException("book " + book.bookId() + " comes twice");
			}
		}
		Map<Long, Line> lineByBook = new HashMap<>();
		int notListed = 0;
		for (Line line : lines) {
			if (lineByBook.containsKey(line.bookId())) {
				throw new IllegalArgumentException("two lines for book " + line.bookId());
			}
			lineByBook.put(line.bookId(), line);
			if (!listed.contains(line.bookId())) {
				notListed++;
			}
		}
		if (lines.isEmpty()) {
			Integer stored = jdbc.queryForObject("SELECT count(line) FROM aisearch.search_book", Integer.class);
			if (stored != null && stored > 0) {
				throw new IllegalStateException("The new file has no search line but " + stored
						+ " books have one now; refused so that no line is lost, the search books were kept");
			}
		}
		int deleted = jdbc.update("DELETE FROM aisearch.search_book");
		int[] withLine = { 0 };
		jdbc.batchUpdate(INSERT, books, 500, (statement, book) -> {
			Line line = lineByBook.get(book.bookId());
			statement.setLong(1, book.bookId());
			statement.setString(2, book.title());
			statement.setString(3, book.author());
			if (line == null) {
				statement.setNull(4, Types.VARCHAR);
				statement.setNull(5, Types.VARCHAR);
				statement.setNull(6, Types.DATE);
			}
			else {
				withLine[0]++;
				statement.setString(4, line.text());
				statement.setString(5, line.source().code());
				statement.setDate(6, Date.valueOf(line.madeOn()));
			}
			statement.setString(7, SearchText.ofBook(book.title(), book.author(), line == null ? null : line.text()));
			statement.setArray(8, statement.getConnection()
				.createArrayOf("integer", Arrays.stream(book.libraryIds()).mapToObj(Math::toIntExact).toArray(Integer[]::new)));
		});
		Result result = new Result(books.size(), withLine[0], notListed);
		log.info("search books replaced: deleted {}, stored {}, with line {}, lines not listed {}", deleted,
				result.stored(), result.withLine(), result.linesNotListed());
		if (notListed > 0) {
			log.warn("{} search lines are for books not searched now: was the file made from another database?",
					notListed);
		}
		return result;
	}

	@Override
	public void compact() {
		if (TransactionSynchronizationManager.isActualTransactionActive()) {
			// VACUUM cannot run in a transaction (the import calls this after its own); the space stays until next time
			log.info("search books not compacted: called inside a transaction");
			return;
		}
		long started = System.nanoTime();
		try {
			jdbc.execute("VACUUM (FULL, ANALYZE) aisearch.search_book");
			log.info("search books compacted in {} ms", (System.nanoTime() - started) / 1_000_000);
		}
		catch (DataAccessException e) {
			// the books are in already; only the space of the replaced rows stays
			log.warn("search books not compacted: {}", e.getClass().getName());
		}
	}

}
