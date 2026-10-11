package com.chaekcheck.popular.internal.web;

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
import com.chaekcheck.popular.internal.PopularProperties;
import com.chaekcheck.popular.internal.cache.PopularCache;
import com.chaekcheck.popular.internal.store.PopularStore;

/**
 * The popular books screen (plan cycle 10 design-delta "API"). No login (R7-3), no library site is called. The lists
 * come from the shared cache; whether a book is in the user's plan libraries is worked out per request and never
 * cached (ADR-0074).
 */
@RestController
class PopularBooksController {

	/** Covers never change under an id: browsers keep them 30 days [확정]. */
	static final String COVER_CACHE_CONTROL = "public, max-age=2592000, immutable";

	private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

	private final PopularCache cache;

	private final PopularStore store;

	private final LibraryCatalog libraryCatalog;

	private final ZoneId zone;

	PopularBooksController(PopularCache cache, PopularStore store, LibraryCatalog libraryCatalog,
			PopularProperties properties) {
		this.cache = cache;
		this.store = store;
		this.libraryCatalog = libraryCatalog;
		this.zone = properties.fetch().zone();
	}

	@GetMapping("/api/v1/popular-books")
	PopularBooks popularBooks(@RequestParam MultiValueMap<String, String> params) {
		PopularPlanParams read = new PopularPlanParams(params);
		PlanOptions options = read.planOptions();
		Set<Long> residence = read.regionIds("residenceRegionIds");
		Set<Long> work = read.regionIds("workRegionIds");
		List<SearchTarget> plan = libraryCatalog.planSearch(residence, work, options);
		Set<Long> planIds = new HashSet<>();
		plan.forEach(target -> planIds.add(target.id()));

		PopularCache.Snapshot snapshot = cache.snapshot();
		if (snapshot.month() == null) {
			return new PopularBooks(null, null, List.of());
		}
		List<PopularBooks.Category> categories = snapshot.categories()
			.stream()
			.map(category -> new PopularBooks.Category(category.code(), category.name(),
					category.books().stream().map(book -> book(book, planIds)).toList()))
			.toList();
		return new PopularBooks(snapshot.month().format(MONTH), snapshot.fetchedAt()
			.atZone(zone)
			.toOffsetDateTime()
			.truncatedTo(ChronoUnit.SECONDS)
			.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), categories);
	}

	@GetMapping("/api/v1/popular-books/covers/{coverId}")
	ResponseEntity<byte[]> cover(@PathVariable String coverId) {
		PopularStore.StoredCover cover = store.cover(parseId(coverId)).orElseThrow(CoverNotFoundException::new);
		return ResponseEntity.ok()
			.contentType(mediaType(cover.contentType()))
			.header(HttpHeaders.CACHE_CONTROL, COVER_CACHE_CONTROL)
			.body(cover.image());
	}

	private static PopularBooks.Book book(PopularCache.Book book, Set<Long> planIds) {
		boolean available = false;
		if (book.bookId() != null) {
			for (long libraryId : book.libraryIds()) {
				if (planIds.contains(libraryId)) {
					available = true;
					break;
				}
			}
		}
		return new PopularBooks.Book(book.rank(), book.title(),
				book.coverId() == null ? null : "/api/v1/popular-books/covers/" + book.coverId(), book.bookId(),
				available);
	}

	/** An id that is not a positive number names no cover: 404 like a missing one. */
	private static long parseId(String raw) {
		if (raw.matches("[1-9][0-9]{0,17}")) {
			return Long.parseLong(raw);
		}
		throw new CoverNotFoundException();
	}

	/** The stored type of the site's answer; a value that is not a media type is sent as bytes. */
	private static MediaType mediaType(String contentType) {
		try {
			return MediaType.parseMediaType(contentType);
		}
		catch (IllegalArgumentException ex) {
			return MediaType.APPLICATION_OCTET_STREAM;
		}
	}

	/**
	 * The response (design-delta "API"): lists in display order with their short names; {@code month} and
	 * {@code fetchedAt} null and no lists when nothing is stored.
	 * @param month {@code YYYY-MM}
	 * @param fetchedAt when the month was stored, ISO 8601 with the Asia/Seoul offset [안]
	 */
	record PopularBooks(String month, String fetchedAt, List<Category> categories) {

		/** @param code {@code ALL} or the site's category id */
		record Category(String code, String name, List<Book> books) {
		}

		/**
		 * @param coverUrl our address of the stored small cover, or null (the web shows the title)
		 * @param bookId our book with the same ISBN, also when no plan library holds it; null without one
		 * @param available a plan library holds our book
		 */
		record Book(int rank, String title, String coverUrl, Long bookId, boolean available) {
		}

	}

}
