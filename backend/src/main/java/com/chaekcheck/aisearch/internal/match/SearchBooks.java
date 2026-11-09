package com.chaekcheck.aisearch.internal.match;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.chaekcheck.aisearch.internal.firstcall.TitleWords;
import com.chaekcheck.catalog.AiSearchIndex;

/**
 * The titles and authors of every book the AI search answers with ({@code catalog.book_search}: the listed books and,
 * since 2026-10-25, the books held by the most libraries), kept in memory for the title line check, the earlier turns
 * and the cards. About 77,000 short rows; the library ids stay in the table ({@link CandidateQuery} counts them).
 * <p>
 * Cycle 29 (ADR-0174): read once when the application is ready and then only when {@link #reload()} is asked
 * ({@code POST /internal/aisearch/books/reload}, sent by the {@code book-texts} and catalog imports when they end). The
 * question path never asks the table whether it changed: the one-minute {@code count(*)} it asked before took 1.3 s
 * after a quiet minute (plan cycle 29 measurements). A reload reads beside the books in use and puts the new set in at
 * once; a failed reload keeps the books in use. Before the first read the first caller reads them itself.
 * <p>
 * Since cycle 26 (ADR-0165) the table is {@code catalog.book_search}, read through {@link AiSearchIndex}, and the title
 * and author are {@code catalog.book}'s ({@code title}, {@code author_display}), not the list's shown name.
 */
@Component
public class SearchBooks {

	private static final Logger log = LoggerFactory.getLogger(SearchBooks.class);

	private final AiSearchIndex index;

	/** The books in use; null until the first read. */
	private volatile Loaded loaded;

	public SearchBooks(AiSearchIndex index) {
		this.index = index;
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

	private record Loaded(Map<Long, SearchBook> byId, List<SearchBook> books) {
	}

	/** Every search book by id. Empty before the first import. */
	public Map<Long, SearchBook> byId() {
		return current().byId();
	}

	/** Every search book, in book id order. */
	public List<SearchBook> books() {
		return current().books();
	}

	private Loaded current() {
		Loaded books = loaded;
		if (books != null) {
			return books;
		}
		synchronized (this) {
			if (loaded == null) {
				try {
					reload();
				}
				catch (RuntimeException e) {
					// no books yet: answer with none this time and read again on the next question
					return new Loaded(Map.of(), List.of());
				}
			}
			return loaded;
		}
	}

	/**
	 * Reads the books again and puts them in at once; the questions meanwhile use the books in use. One reload at a time.
	 * @return how many books were read
	 * @throws RuntimeException when the read failed; the books in use stay (logged here as a warning)
	 */
	public synchronized int reload() {
		long started = System.nanoTime();
		try {
			Loaded read = read();
			loaded = read;
			log.info("ai search books read: {} in {} ms", read.books().size(), (System.nanoTime() - started) / 1_000_000);
			return read.books().size();
		}
		catch (RuntimeException e) {
			log.warn("ai search books not read, the books in use stay: {}", e.getClass().getName());
			throw e;
		}
	}

	private Loaded read() {
		List<SearchBook> books = new ArrayList<>();
		for (AiSearchIndex.SearchBook book : index.books()) {
			books.add(new SearchBook(book.bookId(), book.title(), book.author(), book.publishedYear()));
		}
		// the titles also tell 「코로나」 from 「세계역사나」 (TitleWords)
		TitleWords.use(books.stream().map(book -> book.title().toLowerCase(java.util.Locale.ROOT)).toList());
		Map<Long, SearchBook> byId = new HashMap<>(books.size() * 2);
		for (SearchBook book : books) {
			byId.put(book.bookId(), book);
		}
		return new Loaded(Collections.unmodifiableMap(byId), List.copyOf(books));
	}

}
