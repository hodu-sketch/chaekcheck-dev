package com.chaekcheck.aisearch.internal.match;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * The titles and authors of every book the AI search answers with ({@code aisearch.search_book}: the listed books and,
 * since 2026-10-25, the books held by the most libraries), kept in memory for the title line check, the earlier turns
 * and the cards. About 31,000 short rows; the library ids stay in the table ({@link CandidateQuery} counts them). The
 * {@code book-texts} import replaces the table in another process, so the set is read again when the table's row count
 * or newest {@code imported_at} changed, checked at most once a {@link #CHECK_EVERY}.
 */
@Component
public class SearchBooks {

	/** How often the table is asked whether it changed [안: dev]. */
	static final java.time.Duration CHECK_EVERY = java.time.Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(SearchBooks.class);

	private final JdbcClient jdbc;

	private final Clock clock;

	private volatile Loaded loaded = new Loaded(null, Map.of(), List.of());

	private volatile Instant checkedAt = Instant.EPOCH;

	public SearchBooks(JdbcClient jdbc) {
		this.jdbc = jdbc;
		this.clock = Clock.systemUTC();
	}

	/**
	 * @param title the stored title (the listed books' shown name, else {@code catalog.book.title})
	 * @param author the stored author text, or null
	 * @param titleKey {@link SearchText#letters} of the title, made once when read
	 * @param authorKey {@link SearchText#letters} of the author
	 */
	public record SearchBook(long bookId, String title, String author, String titleKey, String authorKey) {

		public SearchBook(long bookId, String title, String author) {
			this(bookId, title, author, SearchText.letters(title), SearchText.letters(author));
		}

	}

	/** @param version the table's row count and newest import time when read */
	private record Loaded(String version, Map<Long, SearchBook> byId, List<SearchBook> books) {
	}

	/** Every search book by id, read again first when the table changed. Empty before the first import. */
	public Map<Long, SearchBook> byId() {
		return fresh().byId();
	}

	/** Every search book, in book id order. */
	public List<SearchBook> books() {
		return fresh().books();
	}

	private Loaded fresh() {
		Instant now = clock.instant();
		if (now.isBefore(checkedAt.plus(CHECK_EVERY))) {
			return loaded;
		}
		synchronized (this) {
			if (now.isBefore(checkedAt.plus(CHECK_EVERY))) {
				return loaded;
			}
			try {
				String version = jdbc.sql("SELECT count(*) AS n, max(imported_at) AS at FROM aisearch.search_book")
					.query((rs, n) -> rs.getLong("n") + "@" + rs.getObject("at", OffsetDateTime.class))
					.single();
				if (!Objects.equals(version, loaded.version())) {
					loaded = read(version);
					log.info("ai search books read: {}", loaded.books().size());
				}
			}
			catch (RuntimeException e) {
				// keep the books known so far; try again after the next interval
				log.warn("ai search books not read: {}", e.getClass().getName());
			}
			checkedAt = now;
			return loaded;
		}
	}

	private Loaded read(String version) {
		List<SearchBook> books = new ArrayList<>();
		jdbc.sql("SELECT book_id, title, author FROM aisearch.search_book ORDER BY book_id").query(rs -> {
			books.add(new SearchBook(rs.getLong(1), rs.getString(2), rs.getString(3)));
		});
		Map<Long, SearchBook> byId = new HashMap<>(books.size() * 2);
		for (SearchBook book : books) {
			byId.put(book.bookId(), book);
		}
		return new Loaded(version, Collections.unmodifiableMap(byId), List.copyOf(books));
	}

}
