package com.chaekcheck.picks.internal.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookAuthors;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * {@link ListedBooks} from the lists' shared copies (cycle 24). {@link PicksCache} is not changed (cycle 23 works on
 * it at the same time): {@link #current()} reads its copies, and {@link #loadFromDb()} reads the lists with a new
 * cache of its own, so both give the same books and the same shown names.
 * <p>
 * The set is built again only when one of the copies was swapped (every cache refresh swaps them), with one catalog
 * query for the authors. If that query fails, the new books are used with the authors known before (none for a book
 * not seen before), a warning is logged once, and the query is tried again at most once a minute
 * ({@link #AUTHOR_RETRY}), so a database outage does not add a query to every request.
 * <p>
 * TODO(24-F1): read the author from the cache once it carries one (dev docs/plan-feedback.md "후속 작업 24-F1").
 */
@Component
class CacheListedBooks implements ListedBooks {

	/** How long a failed author read waits before it is tried again [안]. */
	static final Duration AUTHOR_RETRY = Duration.ofMinutes(1);

	private static final Logger log = LoggerFactory.getLogger(CacheListedBooks.class);

	private final PicksCache cache;

	private final PicksStore store;

	private final BookLookup bookLookup;

	private final LibraryCatalog libraries;

	private final BookAuthors authors;

	private final Clock clock;

	private Built built;

	@Autowired
	CacheListedBooks(PicksCache cache, PicksStore store, BookLookup bookLookup, LibraryCatalog libraries,
			BookAuthors authors) {
		this(cache, store, bookLookup, libraries, authors, Clock.systemUTC());
	}

	CacheListedBooks(PicksCache cache, PicksStore store, BookLookup bookLookup, LibraryCatalog libraries,
			BookAuthors authors, Clock clock) {
		this.cache = cache;
		this.store = store;
		this.bookLookup = bookLookup;
		this.libraries = libraries;
		this.authors = authors;
		this.clock = clock;
	}

	@Override
	public synchronized Snapshot current() {
		List<PicksCache.Snapshot> copies = copies(cache);
		if (built != null && built.sameCopies(copies)
				&& (built.retryAt() == null || clock.instant().isBefore(built.retryAt()))) {
			return built.snapshot();
		}
		List<Merged> merged = merge(copies);
		try {
			Map<Long, String> found = authors.byIds(merged.stream().map(Merged::bookId).toList());
			built = new Built(copies, snapshot(merged, found::get), found, null);
		}
		catch (RuntimeException ex) {
			Map<Long, String> known = built == null ? Map.of() : built.authors();
			if (built == null || built.retryAt() == null) {
				log.warn("listed books: reading the authors failed, the authors known before are used: {}",
						ex.toString());
			}
			built = new Built(copies, snapshot(merged, known::get), known, clock.instant().plus(AUTHOR_RETRY));
		}
		return built.snapshot();
	}

	@Override
	public Snapshot loadFromDb() {
		PicksCache own = new PicksCache(store, bookLookup, libraries);
		own.refresh();
		List<String> unread = new ArrayList<>();
		for (String list : PicksCache.LISTS) {
			if (own.snapshot(list) == PicksCache.Snapshot.EMPTY && !store.books(list).isEmpty()) {
				unread.add(list);
			}
		}
		if (!unread.isEmpty()) {
			throw new ListsNotReadException(unread);
		}
		List<Merged> merged = merge(copies(own));
		Map<Long, String> found = authors.byIds(merged.stream().map(Merged::bookId).toList());
		return snapshot(merged, found::get);
	}

	private static List<PicksCache.Snapshot> copies(PicksCache cache) {
		return PicksCache.LISTS.stream().map(cache::snapshot).toList();
	}

	/** One row per our book, in list order then each list's order; rows without our book are left out. */
	static List<Merged> merge(List<PicksCache.Snapshot> copies) {
		Map<Long, Merged> byBook = new LinkedHashMap<>();
		for (int i = 0; i < copies.size(); i++) {
			String list = PicksCache.LISTS.get(i);
			PicksCache.Snapshot copy = copies.get(i);
			if (copy.categories().isEmpty()) {
				continue;
			}
			for (PicksCache.Book book : copy.categories().getFirst().books()) {
				if (book.bookId() == null) {
					continue;
				}
				Merged row = byBook.computeIfAbsent(book.bookId(),
						id -> new Merged(id, book.title(), book.libraryIds()));
				if (!row.listKeys.contains(list)) {
					row.listKeys.add(list);
				}
				if (row.cover == null) {
					row.cover = book.coverPath();
				}
			}
		}
		return List.copyOf(byBook.values());
	}

	private Snapshot snapshot(List<Merged> merged, Function<Long, String> author) {
		List<ListedBook> books = merged.stream()
			.map(row -> new ListedBook(row.bookId, row.title, author.apply(row.bookId), row.cover, row.libraryIds,
					row.listKeys))
			.toList();
		return new Snapshot(clock.instant(), books);
	}

	/** A book while the lists are merged: the first list's title, every list key, the first cover found. */
	static final class Merged {

		final long bookId;

		final String title;

		final long[] libraryIds;

		final List<String> listKeys = new ArrayList<>();

		String cover;

		Merged(long bookId, String title, long[] libraryIds) {
			this.bookId = bookId;
			this.title = title;
			this.libraryIds = libraryIds;
		}

		long bookId() {
			return bookId;
		}

	}

	/**
	 * The set, the copies it was built from (the copies are swapped whole, so identity tells a refresh), the authors it
	 * used, and when to read the authors again after a failed read (null when they were read).
	 */
	private record Built(List<PicksCache.Snapshot> copies, Snapshot snapshot, Map<Long, String> authors,
			Instant retryAt) {

		boolean sameCopies(List<PicksCache.Snapshot> now) {
			for (int i = 0; i < now.size(); i++) {
				if (now.get(i) != copies.get(i)) {
					return false;
				}
			}
			return true;
		}

	}

}
