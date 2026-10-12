package com.chaekcheck.picks.internal.web;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.picks.internal.PicksProperties;
import com.chaekcheck.picks.internal.cache.PicksCache;
import com.chaekcheck.picks.internal.store.PicksStore;

/**
 * The librarian picks screen (plan cycle 14 design-delta "API"). No login (R7-3), no library site or National Library
 * request is made. The lists come from the shared cache; whether a book is in the user's plan libraries is worked out
 * per request and never cached (ADR-0090).
 */
@RestController
class PicksController {

	/** Covers never change under an id: browsers keep them 30 days [확정]. */
	static final String COVER_CACHE_CONTROL = "public, max-age=2592000, immutable";

	static final String COVER_PATH = "/api/v1/picks/covers/";

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private final PicksCache cache;

	private final PicksStore store;

	private final LibraryCatalog libraryCatalog;

	private final ZoneId zone;

	PicksController(PicksCache cache, PicksStore store, LibraryCatalog libraryCatalog, PicksProperties properties) {
		this.cache = cache;
		this.store = store;
		this.libraryCatalog = libraryCatalog;
		this.zone = properties.fetch().zone();
	}

	@GetMapping("/api/v1/picks")
	Picks picks(@RequestParam MultiValueMap<String, String> params) {
		PicksPlanParams read = new PicksPlanParams(params);
		PlanOptions options = read.planOptions();
		Set<Long> residence = read.regionIds("residenceRegionIds");
		Set<Long> work = read.regionIds("workRegionIds");
		List<SearchTarget> plan = libraryCatalog.planSearch(residence, work, options);
		Set<Long> planIds = new HashSet<>();
		plan.forEach(target -> planIds.add(target.id()));

		PicksCache.Snapshot snapshot = cache.snapshot();
		if (snapshot.categories().isEmpty()) {
			return new Picks(null, List.of());
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
		return new Picks(fetchedAt, categories);
	}

	@GetMapping(COVER_PATH + "{coverId}")
	ResponseEntity<byte[]> cover(@PathVariable String coverId) {
		PicksStore.StoredCover cover = store.cover(parseId(coverId)).orElseThrow(CoverNotFoundException::new);
		return ResponseEntity.ok()
			.contentType(mediaType(cover.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, COVER_CACHE_CONTROL)
			.body(cover.image());
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
		return new Picks.Book(book.recomNo(), book.category(), book.recommendedMonth().format(MONTH), book.title(),
				book.coverId() == null ? null : COVER_PATH + book.coverId(), book.bookId(), available);
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
	 * The response (design-delta "API"): lists in display order with their short names; {@code fetchedAt} null and no
	 * lists when nothing is stored.
	 * @param fetchedAt the latest successful fetch, ISO 8601 with the Asia/Seoul offset
	 */
	record Picks(String fetchedAt, List<Category> categories) {

		/** @param code {@code ALL} or the API's field code */
		record Category(String code, String name, List<Book> books) {
		}

		/**
		 * @param category the book's field code
		 * @param recommendedMonth {@code YYYY-MM}
		 * @param coverUrl our address of the stored cover, or null (the web shows the title)
		 * @param bookId our book, also when no plan library holds it; null without one
		 * @param available a plan library holds our book
		 */
		record Book(String recomNo, String category, String recommendedMonth, String title, String coverUrl,
				Long bookId, boolean available) {
		}

	}

}
