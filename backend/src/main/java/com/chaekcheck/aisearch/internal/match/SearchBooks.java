package com.chaekcheck.aisearch.internal.match;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.AiSearchIndex;

/**
 * The titles and authors of every book the AI search answers with ({@code catalog.book_search}: the listed books and,
 * since 2026-10-25, the books held by the most libraries), kept in memory for the title line check, the earlier turns
 * and the cards. About 31,000 short rows; the library ids stay in the table ({@link CandidateQuery} counts them). The
 * {@code book-texts} import replaces the table in another process, so the set is read again when the table's row count
 * or newest {@code imported_at} changed, checked at most once a {@link #CHECK_EVERY}.
 * <p>
 * Since cycle 26 (ADR-0165) the table is {@code catalog.book_search}, read through {@link AiSearchIndex}, and the title
 * and author are {@code catalog.book}'s ({@code title}, {@code author_display}), not the list's shown name.
 */
@Component
public class SearchBooks {

	/** How often the table is asked whether it changed [안: dev]. */
	static final java.time.Duration CHECK_EVERY = java.time.Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(SearchBooks.class);

	private final AiSearchIndex index;

	private final Clock clock;

	private volatile Loaded loaded = new Loaded(null, Map.of(), List.of());

	private volatile Instant checkedAt = Instant.EPOCH;

	public SearchBooks(AiSearchIndex index) {
		this.index = index;
		this.clock = Clock.systemUTC();
	}

	/**
	 * @param title {@code catalog.book.title}
	 * @param author the stored author text, or null
	 * @param titleKey {@link SearchText#letters} of the title, made once when read
	 * @param authorKey {@link SearchText#letters} of the author
	 * @param year the publish year, or null (user 2026-10-29: 「최신」 puts recent vector candidates first)
	 */
	public record SearchBook(long bookId, String title, String author, String titleKey, String authorKey, Integer year) {

		public SearchBook(long bookId, String title, String author) {
			this(bookId, title, author, null);
		}

		public SearchBook(long bookId, String title, String author, Integer year) {
			this(bookId, title, author, SearchText.letters(title), SearchText.letters(author), year);
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
				String version = index.version();
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
		for (AiSearchIndex.SearchBook book : index.books()) {
			books.add(new SearchBook(book.bookId(), book.title(), book.author(), book.publishedYear()));
		}
		Map<Long, SearchBook> byId = new HashMap<>(books.size() * 2);
		for (SearchBook book : books) {
			byId.put(book.bookId(), book);
		}
		return new Loaded(version, Collections.unmodifiableMap(byId), List.copyOf(books));
	}

}
