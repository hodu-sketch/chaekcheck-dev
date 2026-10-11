package com.chaekcheck.popular.internal.cache;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.popular.internal.PopularCategory;
import com.chaekcheck.popular.internal.RankingChanged;
import com.chaekcheck.popular.internal.store.PopularStore;

/**
 * One shared copy of the latest month (ADR-0074): the lists in display order with each book's rank, title, stored
 * cover and our book found by ISBN ({@code bookId}, {@code libraryIds}). Read again every 10 minutes [확정], right after
 * a fetch stored a month or its covers, and once at start-up [안]; swapped whole ({@link AtomicReference}). If reading
 * fails, the copy before stays and a warning is logged (plan cycle 10 C8). Nothing per user is kept here: whether a
 * book is in the user's plan is worked out per request.
 * <p>
 * Between storing a month and the end of its covers, a refresh shows the new books without covers (titles) for a
 * while [안, accepted].
 */
@Component
public class PopularCache implements RankingChanged {

	private static final Logger log = LoggerFactory.getLogger(PopularCache.class);

	private final PopularStore store;

	private final BookLookup bookLookup;

	private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);

	PopularCache(PopularStore store, BookLookup bookLookup) {
		this.store = store;
		this.bookLookup = bookLookup;
	}

	/** The copy now; {@link Snapshot#EMPTY} until a month is stored. */
	public Snapshot snapshot() {
		return current.get();
	}

	@EventListener(ApplicationReadyEvent.class)
	void atStartup() {
		refresh();
	}

	@Scheduled(fixedDelayString = "${chaekcheck.popular.cache.refresh-interval}",
			initialDelayString = "${chaekcheck.popular.cache.refresh-interval}")
	void every10Minutes() {
		refresh();
	}

	@Override
	public void rankingChanged() {
		refresh();
	}

	/** Reads the latest month again; on failure the copy before stays. */
	public synchronized void refresh() {
		long start = System.nanoTime();
		try {
			Snapshot next = read();
			current.set(next);
			log.debug("popular cache refreshed: {} in {}ms", next.month(), (System.nanoTime() - start) / 1_000_000);
		}
		catch (RuntimeException ex) {
			log.warn("popular cache refresh failed, the copy before stays: {}", ex.toString());
		}
	}

	private Snapshot read() {
		Optional<PopularStore.StoredMonth> stored = store.readLatestMonth();
		if (stored.isEmpty()) {
			return Snapshot.EMPTY;
		}
		List<String> isbns = stored.get()
			.categories()
			.stream()
			.flatMap(category -> category.books().stream())
			.map(PopularStore.StoredBook::isbn13)
			.filter(isbn -> isbn != null)
			.distinct()
			.toList();
		Map<String, IsbnBook> ours = new HashMap<>();
		bookLookup.byIsbn13(isbns).forEach(book -> ours.put(book.isbn13(), book));
		List<Category> categories = new ArrayList<>();
		for (PopularStore.StoredCategory category : stored.get().categories()) {
			Optional<PopularCategory> known = PopularCategory.byCode(category.code());
			if (known.isEmpty()) {
				continue; // a list no longer shown
			}
			List<Book> books = category.books().stream().map(book -> {
				IsbnBook our = book.isbn13() == null ? null : ours.get(book.isbn13());
				return new Book(book.rank(), book.title(), book.coverId(), our == null ? null : our.bookId(),
						our == null ? new long[0] : our.libraryIds());
			}).toList();
			categories.add(new Category(category.code(), known.get().shortName(), books));
		}
		return new Snapshot(stored.get().month(), stored.get().fetchedAt(), List.copyOf(categories));
	}

	/** @param month null when nothing is stored */
	public record Snapshot(YearMonth month, Instant fetchedAt, List<Category> categories) {

		public static final Snapshot EMPTY = new Snapshot(null, null, List.of());

	}

	/** @param name the short name shown */
	public record Category(String code, String name, List<Book> books) {
	}

	/**
	 * @param coverId the stored cover, or null
	 * @param bookId our book with the same ISBN, or null
	 * @param libraryIds {@code catalog.book.library_ids} of that book; empty without one
	 */
	public record Book(int rank, String title, Long coverId, Long bookId, long[] libraryIds) {
	}

}
