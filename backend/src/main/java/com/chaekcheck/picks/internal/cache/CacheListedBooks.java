package com.chaekcheck.picks.internal.cache;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookAuthors;
import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.BookTagTransfer.Tag;
import com.chaekcheck.picks.FieldGroups;
import com.chaekcheck.picks.ListedBooks;
import com.chaekcheck.picks.internal.tags.CardTags;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * {@link ListedBooks} from the lists' shared copies (cycle 24). {@link PicksCache} is not changed (cycle 23 works on
 * it at the same time): {@link #current()} reads its copies, and {@link #loadFromDb()} reads the lists with a new
 * cache of its own, so both give the same books and the same shown names.
 * <p>
 * The set is built again only when one of the copies was swapped (every cache refresh swaps them), with one catalog
 * query for the authors and one picks query for the card tags (cycle 23). If either fails, the new books are used
 * with the authors and tags known before (none for a book not seen before), a warning is logged once, and the queries
 * are tried again at most once a minute ({@link #AUTHOR_RETRY}), so a database outage does not add a query to every
 * request.
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

	private final CardTags cardTags;

	private final Clock clock;

	private Built built;

	@Autowired
	CacheListedBooks(PicksCache cache, PicksStore store, BookLookup bookLookup, LibraryCatalog libraries,
			BookAuthors authors, CardTags cardTags) {
		this(cache, store, bookLookup, libraries, authors, cardTags, Clock.systemUTC());
	}

	CacheListedBooks(PicksCache cache, PicksStore store, BookLookup bookLookup, LibraryCatalog libraries,
			BookAuthors authors, CardTags cardTags, Clock clock) {
		this.cache = cache;
		this.store = store;
		this.bookLookup = bookLookup;
		this.libraries = libraries;
		this.authors = authors;
		this.cardTags = cardTags;
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
			Extras found = extras(merged);
			built = new Built(copies, snapshot(merged, found), found, null);
		}
		catch (RuntimeException ex) {
			Extras known = built == null ? Extras.NONE : built.extras();
			if (built == null || built.retryAt() == null) {
				log.warn("listed books: reading the authors or tags failed, the ones known before are used: {}",
						ex.toString());
			}
			built = new Built(copies, snapshot(merged, known), known, clock.instant().plus(AUTHOR_RETRY));
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
		return snapshot(merged, extras(merged));
	}

	private Extras extras(List<Merged> merged) {
		List<Long> ids = merged.stream().map(Merged::bookId).toList();
		return new Extras(authors.byIds(ids), cardTags.byIds(ids));
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
				if (row.aiSummary == null) {
					row.aiSummary = book.aiSummary();
				}
				// the book windows' year lines (cycle 23): the librarian list's month, KAIST's year
				if (list.equals(PicksCache.LIBRARIAN) && row.recommendedMonth == null) {
					row.recommendedMonth = book.recommendedMonth();
				}
				if (list.equals(PicksCache.KAIST) && row.yearBook == null) {
					row.yearBook = book.yearBook();
				}
				if (row.fieldGroup == null) {
					row.fieldGroup = FieldGroups.of(list, book.category());
				}
			}
		}
		return List.copyOf(byBook.values());
	}

	private Snapshot snapshot(List<Merged> merged, Extras extras) {
		List<ListedBook> books = merged.stream()
			.map(row -> new ListedBook(row.bookId, row.title, extras.authors().get(row.bookId), row.cover,
					row.libraryIds, row.listKeys, row.aiSummary, extras.tags().get(row.bookId), row.recommendedMonth,
					row.yearBook, row.fieldGroup))
			.toList();
		return new Snapshot(clock.instant(), books);
	}

	/**
	 * A book while the lists are merged: the first list's title, every list key, the first cover and AI summary found,
	 * the librarian list's month and KAIST's year, the first list's field group.
	 */
	static final class Merged {

		final long bookId;

		final String title;

		final long[] libraryIds;

		final List<String> listKeys = new ArrayList<>();

		String cover;

		String aiSummary;

		YearMonth recommendedMonth;

		Integer yearBook;

		String fieldGroup;

		Merged(long bookId, String title, long[] libraryIds) {
			this.bookId = bookId;
			this.title = title;
			this.libraryIds = libraryIds;
		}

		long bookId() {
			return bookId;
		}

	}

	/** Our books' authors and card tags, read once per set. */
	private record Extras(Map<Long, String> authors, Map<Long, List<Tag>> tags) {

		static final Extras NONE = new Extras(Map.of(), Map.of());

	}

	/**
	 * The set, the copies it was built from (the copies are swapped whole, so identity tells a refresh), the authors
	 * and tags it used, and when to read them again after a failed read (null when they were read).
	 */
	private record Built(List<PicksCache.Snapshot> copies, Snapshot snapshot, Extras extras, Instant retryAt) {

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
