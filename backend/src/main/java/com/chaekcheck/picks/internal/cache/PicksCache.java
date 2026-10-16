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
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.chaekcheck.catalog.BookLookup;
import com.chaekcheck.catalog.IdBook;
import com.chaekcheck.catalog.IsbnBook;
import com.chaekcheck.catalog.TitleAuthor;
import com.chaekcheck.catalog.TitleAuthorBook;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.picks.internal.PicksChanged;
import com.chaekcheck.picks.internal.store.PicksStore;
import com.chaekcheck.picks.internal.store.PicksStore.StoredBook;

/**
 * One shared copy per recommended list (ADR-0090, ADR-0122): its fields in display order with each book's field,
 * shown name, stored cover and our book ({@code bookId}, {@code libraryIds}). Read again every 10 minutes [확정], right
 * after a fetch changed the librarian list or its covers, and once at start-up; each list is swapped whole. If
 * reading a list fails (also while the catalog is swapped, plan cycle 14 C10), that list's copy before stays and a
 * warning is logged (C9). Nothing per user is kept here: whether a book is in the user's plan is worked out per
 * request.
 * <p>
 * Our book: the librarian rows are matched here, first by ISBN ({@code byIsbn13}, once), then the rest by title and
 * first author ({@code byTitleAndAuthor}, once), several matches narrowed by {@link BookChoice}; the other lists
 * carry the book the import matched ({@code list_book.book_id}, read with {@code byIds}). The shown name is our book's
 * title without its edition marks ({@link ShownTitle}, ADR-0129), else the list's title; {@code listTitle} is the
 * list's title when it differs.
 * <p>
 * Order: librarian books by recommendation month, registration date (unknown last), then {@code recomNo} as text,
 * each latest first (cycle 14 R2, R4-2); Lee Dong-jin by month (latest first), then the order inside the month; SNU
 * by the table-of-contents number. Fields: "전체" (every book), then {@code list_field} order; empty ones are left out.
 */
@Component
public class PicksCache implements PicksChanged {

	public static final String LIBRARIAN = "librarian";

	public static final String LEEDONGJIN = "leedongjin";

	public static final String SNU100 = "snu100";

	/** The lists, as {@code GET /api/v1/picks/{listKey}} names them (ADR-0126). */
	public static final List<String> LISTS = List.of(LIBRARIAN, LEEDONGJIN, SNU100);

	static final String ALL_CODE = "ALL";

	static final String ALL_NAME = "전체";

	static final Comparator<StoredBook> LIBRARIAN_ORDER = Comparator
		.comparing(StoredBook::recommendedMonth, Comparator.reverseOrder())
		.thenComparing(StoredBook::registeredOn, Comparator.nullsLast(Comparator.reverseOrder()))
		.thenComparing(StoredBook::sourceNo, Comparator.reverseOrder());

	static final Comparator<StoredBook> LEEDONGJIN_ORDER = Comparator
		.comparing(StoredBook::recommendedMonth, Comparator.reverseOrder())
		.thenComparingInt(StoredBook::position);

	static final Comparator<StoredBook> SNU100_ORDER = Comparator.comparing(StoredBook::tocNo);

	private static final Logger log = LoggerFactory.getLogger(PicksCache.class);

	private final PicksStore store;

	private final BookLookup bookLookup;

	private final LibraryCatalog libraries;

	private final Map<String, Snapshot> current = new ConcurrentHashMap<>();

	PicksCache(PicksStore store, BookLookup bookLookup, LibraryCatalog libraries) {
		this.store = store;
		this.bookLookup = bookLookup;
		this.libraries = libraries;
	}

	/** The copy of one list now; {@link Snapshot#EMPTY} until it is stored, and for a name that is not a list. */
	public Snapshot snapshot(String listKey) {
		return current.getOrDefault(listKey, Snapshot.EMPTY);
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

	/** Reads every list again; a list that fails keeps its copy before. */
	public synchronized void refresh() {
		Map<String, List<Field>> fields;
		Map<Long, Boolean> kinds;
		try {
			fields = fields();
			kinds = libraries.lendingKinds();
		}
		catch (RuntimeException ex) {
			log.warn("picks cache refresh failed, the copies before stay: {}", ex.toString());
			return;
		}
		for (String list : LISTS) {
			long start = System.nanoTime();
			try {
				Snapshot next = read(list, fields.getOrDefault(list, List.of()), kinds);
				current.put(list, next);
				log.info("picks cache refreshed: {} {} books, {} with our book, in {}ms", list, next.bookCount(),
						next.matchedCount(), (System.nanoTime() - start) / 1_000_000);
			}
			catch (RuntimeException ex) {
				log.warn("picks cache refresh of {} failed, the copy before stays: {}", list, ex.toString());
			}
		}
	}

	private Map<String, List<Field>> fields() {
		Map<String, List<Field>> fields = new HashMap<>();
		for (PicksStore.StoredField field : store.fields()) {
			fields.computeIfAbsent(field.listKey(), list -> new ArrayList<>())
				.add(new Field(field.code(), field.name()));
		}
		return fields;
	}

	private Snapshot read(String list, List<Field> fields, Map<Long, Boolean> kinds) {
		List<StoredBook> stored = new ArrayList<>(store.books(list));
		if (stored.isEmpty()) {
			return Snapshot.EMPTY;
		}
		Map<StoredBook, Ours> ours;
		Instant fetchedAt = null;
		switch (list) {
			case LIBRARIAN -> {
				stored.sort(LIBRARIAN_ORDER);
				ours = matchLibrarian(stored, kinds);
				fetchedAt = store.lastSucceededAt().orElse(null);
			}
			case LEEDONGJIN -> {
				stored.sort(LEEDONGJIN_ORDER);
				ours = byIds(stored);
			}
			default -> {
				stored.sort(SNU100_ORDER);
				ours = byIds(stored);
			}
		}
		List<Book> books = stored.stream().map(row -> book(list, row, ours.get(row))).toList();

		List<Category> categories = new ArrayList<>();
		categories.add(new Category(ALL_CODE, ALL_NAME, books));
		Map<String, List<Book>> byField = new LinkedHashMap<>();
		fields.forEach(field -> byField.put(field.code(), new ArrayList<>()));
		for (Book book : books) {
			List<Book> inField = byField.get(book.category());
			if (inField != null) {
				inField.add(book);
			}
		}
		for (Field field : fields) {
			List<Book> inField = byField.get(field.code());
			if (!inField.isEmpty()) {
				categories.add(new Category(field.code(), field.name(), List.copyOf(inField)));
			}
		}
		return new Snapshot(fetchedAt, List.copyOf(categories));
	}

	private static Book book(String list, StoredBook row, Ours our) {
		String shown = our == null ? row.title() : Objects.requireNonNullElse(ShownTitle.of(our.title()), row.title());
		String listTitle = shown.equals(row.title()) ? null : row.title();
		String id = list.equals(LIBRARIAN) ? row.sourceNo() : Long.toString(row.id());
		YearMonth month = row.recommendedMonth() == null ? null : YearMonth.from(row.recommendedMonth());
		return new Book(id, row.fieldCode(), month, shown, listTitle, row.coverId(),
				our == null ? null : our.bookId(), our == null ? new long[0] : our.libraryIds(), row.yearBook(),
				row.videoId(), row.tocNo());
	}

	/** The books the import matched, read by id; a book gone from the catalog since then is no match. */
	private Map<StoredBook, Ours> byIds(List<StoredBook> rows) {
		List<Long> ids = rows.stream().map(StoredBook::bookId).filter(Objects::nonNull).distinct().toList();
		Map<Long, IdBook> found = new HashMap<>();
		for (IdBook book : bookLookup.byIds(ids)) {
			found.put(book.bookId(), book);
		}
		Map<StoredBook, Ours> ours = new HashMap<>();
		for (StoredBook row : rows) {
			IdBook book = row.bookId() == null ? null : found.get(row.bookId());
			if (book != null) {
				ours.put(row, new Ours(book.bookId(), book.title(), book.libraryIds()));
			}
		}
		return ours;
	}

	/** Our book per librarian row: by ISBN, then the rest by title and first author. */
	private Map<StoredBook, Ours> matchLibrarian(List<StoredBook> rows, Map<Long, Boolean> kinds) {
		Map<String, List<BookChoice.Candidate>> byIsbn = new HashMap<>();
		List<String> isbns = rows.stream().map(StoredBook::isbn13).filter(Objects::nonNull).distinct().toList();
		for (IsbnBook book : bookLookup.byIsbn13(isbns)) {
			byIsbn.computeIfAbsent(book.isbn13(), isbn -> new ArrayList<>())
				.add(new BookChoice.Candidate(book.bookId(), book.title(), book.libraryIds()));
		}
		Map<StoredBook, Ours> ours = new HashMap<>();
		Map<TitleAuthor, List<StoredBook>> rest = new LinkedHashMap<>();
		for (StoredBook row : rows) {
			Optional<BookChoice.Candidate> found = row.isbn13() == null ? Optional.empty()
					: BookChoice.choose(byIsbn.getOrDefault(row.isbn13(), List.of()), kinds);
			if (found.isPresent()) {
				ours.put(row, Ours.of(found.get()));
			}
			else {
				rest.computeIfAbsent(new TitleAuthor(row.title(), row.author()), query -> new ArrayList<>()).add(row);
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
		rest.forEach((query, same) -> BookChoice.choose(byTitle.getOrDefault(query, List.of()), kinds)
			.ifPresent(found -> same.forEach(row -> ours.put(row, Ours.of(found)))));
		return ours;
	}

	private record Field(String code, String name) {
	}

	private record Ours(long bookId, String title, long[] libraryIds) {

		static Ours of(BookChoice.Candidate candidate) {
			return new Ours(candidate.bookId(), candidate.title(), candidate.libraryIds());
		}

	}

	/** @param fetchedAt the latest successful fetch of the librarian picks; null for the other lists */
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
	 * @param id the librarian's {@code recomNo}, the other lists' {@code list_book.id}
	 * @param category the book's field code
	 * @param recommendedMonth librarian and Lee Dong-jin; null for SNU
	 * @param title the shown name (ADR-0129)
	 * @param listTitle the list's own title when it differs from {@code title}, else null
	 * @param coverId the stored cover, or null
	 * @param bookId our book, or null
	 * @param libraryIds {@code catalog.book.library_ids} of that book; empty without one
	 */
	public record Book(String id, String category, YearMonth recommendedMonth, String title, String listTitle,
			Long coverId, Long bookId, long[] libraryIds, Integer yearBook, String videoId, Integer tocNo) {
	}

}
