package com.chaekcheck.picks.internal.cache;

import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.internal.PickCategory;
import com.chaekcheck.picks.internal.PicksChanged;
import com.chaekcheck.picks.internal.fetch.KeptPicks;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * One shared copy of the stored picks (ADR-0090): the lists in display order with each book's field, month, title,
 * stored cover and our book ({@code bookId}, {@code libraryIds}), matched first by ISBN ({@code byIsbn13}, once), then
 * the rest by title and first author ({@code byTitleAndAuthor}, once); several matches are narrowed by
 * {@link BookChoice}. Read again every 10 minutes [확정], right after a fetch changed the list or its covers, and once
 * at start-up; swapped whole ({@link AtomicReference}). If reading fails (also while the catalog is swapped, plan
 * cycle 14 C10), the copy before stays and a warning is logged (C9). Nothing per user is kept here: whether a book is
 * in the user's plan is worked out per request.
 * <p>
 * Order (spec R2, R4-2) [확정]: books by recommendation month (latest first), registration date (latest first, unknown
 * last [안]), then {@code recomNo} as text (largest first). Lists: "전체" (every book), the known fields in
 * {@link PickCategory} order, then unknown field codes in {@link KeptPicks#CODE_ORDER}, named by the API's field name
 * or "기타". Empty lists are left out.
 */
@Component
public class PicksCache implements PicksChanged {

	/** Name of an unknown field without a name from the API [확정]. */
	static final String OTHER_NAME = "기타";

	static final Comparator<PicksStore.StoredPick> BOOK_ORDER = Comparator
		.comparing(PicksStore.StoredPick::recomMonth, Comparator.reverseOrder())
		.thenComparing(PicksStore.StoredPick::registeredOn, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(PicksStore.StoredPick::recomNo, Comparator.reverseOrder());

	private static final Logger log = LoggerFactory.getLogger(PicksCache.class);

	private final PicksStore store;

	private final BookLookup bookLookup;

	private final LibraryCatalog libraries;

	private final AtomicReference<Snapshot> current = new AtomicReference<>(Snapshot.EMPTY);

	PicksCache(PicksStore store, BookLookup bookLookup, LibraryCatalog libraries) {
		this.store = store;
		this.bookLookup = bookLookup;
		this.libraries = libraries;
	}

	/** The copy now; {@link Snapshot#EMPTY} until a list is stored. */
	public Snapshot snapshot() {
		return current.get();
	}

	@EventListener(ApplicationReadyEvent.class)
	void atStartup() {
		refresh();
	}

	@Scheduled(fixedDelayString = "${chaekcheck.picks.cache.refresh-interval}",
			initialDelayString = "${chaekcheck.picks.cache.refresh-interval}")
	void every10Minutes() {
		refresh();
	}

	@Override
	public void picksChanged() {
		refresh();
	}

	/** Reads the stored list again; on failure the copy before stays. */
	public synchronized void refresh() {
		long start = System.nanoTime();
		try {
			Snapshot next = read();
			current.set(next);
			log.info("picks cache refreshed: {} books, {} with our book, in {}ms", next.bookCount(),
					next.matchedCount(), (System.nanoTime() - start) / 1_000_000);
		}
		catch (RuntimeException ex) {
			log.warn("picks cache refresh failed, the copy before stays: {}", ex.toString());
		}
	}

	private Snapshot read() {
		List<PicksStore.StoredPick> stored = new ArrayList<>(store.readPicks());
		if (stored.isEmpty()) {
			return Snapshot.EMPTY;
		}
		Instant fetchedAt = store.lastSucceededAt().orElse(null);
		stored.sort(BOOK_ORDER);
		Map<String, BookChoice.Candidate> ours = match(stored, libraries.lendingKinds());

		List<Book> books = stored.stream().map(pick -> {
			BookChoice.Candidate our = ours.get(pick.recomNo());
			return new Book(pick.recomNo(), pick.drCode(), YearMonth.from(pick.recomMonth()), pick.title(),
					pick.coverId(), our == null ? null : our.bookId(), our == null ? new long[0] : our.libraryIds());
		}).toList();

		List<Category> categories = new ArrayList<>();
		categories.add(new Category(PickCategory.ALL.code(), PickCategory.ALL.shortName(), books));
		Map<String, List<Book>> known = new LinkedHashMap<>();
		for (PickCategory category : PickCategory.values()) {
			if (category != PickCategory.ALL) {
				known.put(category.code(), new ArrayList<>());
			}
		}
		Map<String, List<Book>> unknown = new TreeMap<>(KeptPicks.CODE_ORDER);
		for (Book book : books) {
			List<Book> list = known.get(book.category());
			if (list == null) {
				list = unknown.computeIfAbsent(book.category(), code -> new ArrayList<>());
			}
			list.add(book);
		}
		for (PickCategory category : PickCategory.values()) {
			List<Book> list = known.get(category.code());
			if (list != null && !list.isEmpty()) {
				categories.add(new Category(category.code(), category.shortName(), List.copyOf(list)));
			}
		}
		unknown.forEach((code, list) -> categories.add(new Category(code, unknownName(code, stored), List.copyOf(list))));
		return new Snapshot(fetchedAt, List.copyOf(categories));
	}

	/** Our book per {@code recomNo}: by ISBN, then the rest by title and first author. */
	private Map<String, BookChoice.Candidate> match(List<PicksStore.StoredPick> picks, Map<Long, Boolean> kinds) {
		Map<String, List<BookChoice.Candidate>> byIsbn = new HashMap<>();
		List<String> isbns = picks.stream().map(PicksStore.StoredPick::isbn13).filter(Objects::nonNull).distinct().toList();
		for (IsbnBook book : bookLookup.byIsbn13(isbns)) {
			byIsbn.computeIfAbsent(book.isbn13(), isbn -> new ArrayList<>())
				.add(new BookChoice.Candidate(book.bookId(), book.title(), book.libraryIds()));
		}
		Map<String, BookChoice.Candidate> ours = new HashMap<>();
		Map<TitleAuthor, List<String>> rest = new LinkedHashMap<>();
		for (PicksStore.StoredPick pick : picks) {
			Optional<BookChoice.Candidate> found = pick.isbn13() == null ? Optional.empty()
					: BookChoice.choose(byIsbn.getOrDefault(pick.isbn13(), List.of()), kinds);
			if (found.isPresent()) {
				ours.put(pick.recomNo(), found.get());
			}
			else {
				rest.computeIfAbsent(new TitleAuthor(pick.title(), pick.author()), query -> new ArrayList<>())
					.add(pick.recomNo());
			}
		}
		if (rest.isEmpty()) {
			return ours;
		}
		Map<TitleAuthor, List<BookChoice.Candidate>> byTitle = new HashMap<>();
		for (TitleAuthorBook book : bookLookup.byTitleAndAuthor(rest.keySet())) {
			byTitle.computeIfAbsent(book.query(), query -> new ArrayList<>())
				.add(new BookChoice.Candidate(book.bookId(), book.title(), book.libraryIds()));
		}
		rest.forEach((query, recomNos) -> BookChoice.choose(byTitle.getOrDefault(query, List.of()), kinds)
			.ifPresent(found -> recomNos.forEach(recomNo -> ours.put(recomNo, found))));
		return ours;
	}

	private static String unknownName(String code, List<PicksStore.StoredPick> picks) {
		return picks.stream()
			.filter(pick -> pick.drCode().equals(code) && pick.drCodeName() != null)
			.map(PicksStore.StoredPick::drCodeName)
			.findFirst()
			.orElse(OTHER_NAME);
	}

	/** @param fetchedAt the latest successful fetch; null when nothing is stored */
	public record Snapshot(Instant fetchedAt, List<Category> categories) {

		public static final Snapshot EMPTY = new Snapshot(null, List.of());

		int bookCount() {
			return categories.isEmpty() ? 0 : categories.getFirst().books().size();
		}

		long matchedCount() {
			return categories.isEmpty() ? 0 : categories.getFirst().books().stream().filter(b -> b.bookId() != null).count();
		}

	}

	/** @param name the short name shown */
	public record Category(String code, String name, List<Book> books) {
	}

	/**
	 * @param category the book's field code
	 * @param coverId the stored cover, or null
	 * @param bookId our book, or null
	 * @param libraryIds {@code catalog.book.library_ids} of that book; empty without one
	 */
	public record Book(String recomNo, String category, YearMonth recommendedMonth, String title, Long coverId,
			Long bookId, long[] libraryIds) {
	}

}
