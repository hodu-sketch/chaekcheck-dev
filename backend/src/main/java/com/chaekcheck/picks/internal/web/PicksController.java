package com.chaekcheck.picks.internal.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.random.RandomGenerator;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.library.Eligibility;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.cache.MainShelf;
import com.chaekcheck.picks.internal.cache.PicksCache;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * The recommended lists' screens (plan cycle 14 design-delta "API"; cycle 21 ADR-0126: one address per list,
 * {@code librarian}, {@code leedongjin}, {@code snu100}, {@code kaist} since user 2026-10-19 and {@code nobel} since user 2026-10-20; any other name 404 {@code list_not_found}). No login (R7-3), no
 * library site or National Library request is made. The lists come from the shared cache; whether a book is in the user's plan libraries is worked out
 * per request and never cached (ADR-0090).
 */
@RestController
class PicksController {

	/** Covers never change under an id: browsers keep them 30 days [확정]. */
	static final String COVER_CACHE_CONTROL = "public, max-age=2592000, immutable";

	static final String COVER_PATH = PicksCache.COVER_API;

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private final PicksCache cache;

	private final PicksStore store;

	private final LibraryCatalog libraryCatalog;

	private final ZoneId zone;

	private final RandomGenerator random = RandomGenerator.getDefault();

	PicksController(PicksCache cache, PicksStore store, LibraryCatalog libraryCatalog, PicksProperties properties) {
		this.cache = cache;
		this.store = store;
		this.libraryCatalog = libraryCatalog;
		this.zone = properties.fetch().zone();
	}

	/**
	 * The first screen's shelf (user 2026-10-22): one KAIST book per field of its newest year, drawn from the field's
	 * ten most held ({@link MainShelf}). A new draw on every request, so it is never cached.
	 */
	@GetMapping("/api/v1/picks/shelf")
	ResponseEntity<Shelf> shelf(@RequestParam MultiValueMap<String, String> params) {
		Set<Long> planIds = planIds(params);
		// the libraries anyone may join: a book on the shelf is held by at least one of them (user 2026-10-22)
		Set<Long> open = new HashSet<>();
		libraryCatalog.planSearch(Set.of(), Set.of())
			.stream()
			.filter(target -> target.eligibility() == Eligibility.OPEN)
			.forEach(target -> open.add(target.id()));
		List<Picks.Book> books = MainShelf.choose(cache.snapshot(PicksCache.KAIST), open, random)
			.stream()
			.map(book -> book(book, planIds))
			.toList();
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Shelf(PicksCache.KAIST, books));
	}

	@GetMapping("/api/v1/picks/{listKey}")
	Picks picks(@PathVariable String listKey, @RequestParam MultiValueMap<String, String> params) {
		if (!PicksCache.LISTS.contains(listKey)) {
			throw new ListNotFoundException();
		}
		Set<Long> planIds = planIds(params);

		PicksCache.Snapshot snapshot = cache.snapshot(listKey);
		if (snapshot.categories().isEmpty()) {
			return new Picks(listKey, null, List.of());
		}
		List<Picks.Category> categories = snapshot.categories()
			.stream()
			.map(category -> new Picks.Category(category.code(), category.name(),
					category.books().stream().map(book -> book(book, planIds)).toList()))
			.toList();
		String fetchedAt = snapshot.fetchedAt() == null ? null
				: snapshot.fetchedAt()
					.atZone(zone)
					.toOffsetDateTime()
					.truncatedTo(ChronoUnit.SECONDS)
					.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
		return new Picks(listKey, fetchedAt, categories);
	}

	@GetMapping(COVER_PATH + "{coverId}")
	ResponseEntity<byte[]> cover(@PathVariable String coverId) {
		PicksStore.StoredCover cover = store.cover(parseId(coverId)).orElseThrow(CoverNotFoundException::new);
		return ResponseEntity.ok()
			.contentType(mediaType(cover.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, COVER_CACHE_CONTROL)
			.body(cover.image());
	}

	/** The ids of the libraries the plan in the parameters searches (the same as a search's, ADR-0090). */
	private Set<Long> planIds(MultiValueMap<String, String> params) {
		PicksPlanParams read = new PicksPlanParams(params);
		PlanOptions options = read.planOptions();
		Set<Long> residence = read.regionIds("residenceRegionIds");
		Set<Long> work = read.regionIds("workRegionIds");
		List<SearchTarget> plan = libraryCatalog.planSearch(residence, work, options);
		Set<Long> planIds = new HashSet<>();
		plan.forEach(target -> planIds.add(target.id()));
		return planIds;
	}

	private static Picks.Book book(PicksCache.Book book, Set<Long> planIds) {
		boolean available = false;
		if (book.bookId() != null) {
			for (long libraryId : book.libraryIds()) {
				if (planIds.contains(libraryId)) {
					available = true;
					break;
				}
			}
		}
		return new Picks.Book(book.id(), book.category(), book.title(), book.listTitle(),
				book.coverPath(), book.bookId(), available,
				book.recommendedMonth() == null ? null : book.recommendedMonth().format(MONTH), book.yearBook(),
				book.videoId(), book.tocNo(), book.aiSummary());
	}

	/** An id that is not a positive number names no cover: 404 like a missing one. */
	private static long parseId(String raw) {
		if (raw.matches("[1-9][0-9]{0,17}")) {
			return Long.parseLong(raw);
		}
		throw new CoverNotFoundException();
	}

	/** The stored type; a value that is not a media type is sent as bytes. */
	private static MediaType mediaType(String contentType) {
		try {
			return MediaType.parseMediaType(contentType);
		}
		catch (IllegalArgumentException ex) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	/**
	 * The response (cycle 21 design-delta "API"): one list's fields in display order with their short names; no
	 * fields when nothing is stored.
	 * @param list the list's name, as in the address
	 * @param fetchedAt the latest successful fetch of the librarian picks, ISO 8601 with the Asia/Seoul offset; null
	 * for the other lists and when nothing is stored
	 */
	/**
	 * The first screen's shelf.
	 * @param list the list the books are from (opens its book window)
	 * @param books one per field, in the fields' order
	 */
	record Shelf(String list, List<Picks.Book> books) {
	}

	record Picks(String list, String fetchedAt, List<Category> categories) {

		/** @param code {@code ALL} or the list's field code */
		record Category(String code, String name, List<Book> books) {
		}

		/**
		 * @param id the librarian's {@code recomNo}, the other lists' row id
		 * @param category the book's field code
		 * @param title the shown name: our book's title without edition marks, else the list's title (ADR-0129)
		 * @param listTitle the list's title when it differs from {@code title}, else null
		 * @param coverUrl our address of the stored cover, or null (the web shows the title)
		 * @param bookId our book, also when no plan library holds it; null without one
		 * @param available a plan library holds our book
		 * @param recommendedMonth {@code YYYY-MM} (librarian, Lee Dong-jin), else null
		 * @param yearBook the year of Lee Dong-jin's books of the year it was in, else null
		 * @param videoId Lee Dong-jin's video, else null
		 * @param tocNo the SNU table-of-contents number, else null
		 * @param aiSummary a short AI-written summary of the book (the web says so behind an ⓘ), else null
		 */
		record Book(String id, String category, String title, String listTitle, String coverUrl, Long bookId,
				boolean available, String recommendedMonth, Integer yearBook, String videoId, Integer tocNo,
				String aiSummary) {
		}

	}

}
