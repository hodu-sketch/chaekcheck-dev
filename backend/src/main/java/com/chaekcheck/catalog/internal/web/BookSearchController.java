package com.chaekcheck.catalog.internal.web;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.chaekcheck.catalog.internal.search.BookQuery;
import com.chaekcheck.catalog.internal.search.BookSearch;
import com.chaekcheck.catalog.internal.search.BookSearch.BookDetail;
import com.chaekcheck.catalog.internal.search.BookSearch.BookPage;
import com.chaekcheck.catalog.normalize.Contributors.Role;
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;
import com.chaekcheck.usagelog.SearchConditions;
import com.chaekcheck.usagelog.UsageLog;
import com.chaekcheck.usagelog.UsageRequest;

/**
 * Book search on the collected catalog (ADR-0030, design-delta API). No library site is requested here; the holding
 * stream in the availability module does that.
 * <p>
 * Cycle 15: a search that was answered is logged ({@code usagelog}): page 1 as a search with its total count, a later
 * page as "more". A book page that was found is logged as a view. Requests that end in 400, 404 or 422 are not.
 */
@RestController
class BookSearchController {

	private final BookSearch bookSearch;

	private final LibraryCatalog libraryCatalog;

	private final UsageLog usageLog;

	BookSearchController(BookSearch bookSearch, LibraryCatalog libraryCatalog, UsageLog usageLog) {
		this.bookSearch = bookSearch;
		this.libraryCatalog = libraryCatalog;
		this.usageLog = usageLog;
	}

	@GetMapping("/api/v1/books")
	BookPage books(@RequestParam MultiValueMap<String, String> params, HttpServletRequest request) {
		UsageRequest usage = usageLog.capture(request);
		BookParams read = new BookParams(params);
		BookQuery query = read.query();
		BookPage page = bookSearch.search(query, plan(read));
		SearchConditions conditions = conditions(query, read);
		if (query.page() == 1) {
			usageLog.recordSearch(usage, UsageLog.SearchKind.CATALOG, conditions,
					(int) Math.min(page.totalCount(), Integer.MAX_VALUE));
		}
		else {
			usageLog.recordMore(usage, UsageLog.MoreKind.CATALOG_NEXT_PAGE, query.page(), null, conditions);
		}
		return page;
	}

	@GetMapping("/api/v1/books/{bookId}")
	BookDetail book(@PathVariable String bookId, @RequestParam MultiValueMap<String, String> params,
			HttpServletRequest request) {
		UsageRequest usage = usageLog.capture(request);
		long id = parseId(bookId);
		BookParams read = new BookParams(params == null ? new LinkedMultiValueMap<>() : params);
		List<SearchTarget> plan = plan(read);
		BookDetail detail = bookSearch.find(id, plan).orElseThrow(() -> new BookNotFoundException(id));
		usageLog.recordBookView(usage, id, List.copyOf(read.regionIds("residenceRegionIds")),
				List.copyOf(read.regionIds("workRegionIds")), List.copyOf(read.planOptions().libraryIds()));
		return detail;
	}

	/** The conditions as read (spec R1-2): exact fields and extra roles in a fixed order, lower-case names. */
	private static SearchConditions conditions(BookQuery query, BookParams read) {
		List<String> exact = new ArrayList<>();
		if (query.exactTitle()) {
			exact.add("title");
		}
		if (query.exactAuthor()) {
			exact.add("author");
		}
		if (query.exactPublisher()) {
			exact.add("publisher");
		}
		List<String> roles = new ArrayList<>();
		for (Role role : List.of(Role.TRANSLATOR, Role.ILLUSTRATOR, Role.EDITOR)) {
			if (query.roles().contains(role)) {
				roles.add(lower(role));
			}
		}
		PlanOptions options = read.planOptions();
		return SearchConditions.catalog(query.title(), query.author(), query.publisher(), exact, roles,
				lower(query.sort()), lower(options.scope()), lower(options.visit()),
				read.regionIds("residenceRegionIds"), read.regionIds("workRegionIds"), options.libraryIds());
	}

	private static String lower(Enum<?> value) {
		return value.name().toLowerCase(Locale.ROOT);
	}

	/** Plan libraries after scope and visit; regions are read last so their errors come last (422). */
	private List<SearchTarget> plan(BookParams read) {
		PlanOptions options = read.planOptions();
		Set<Long> residence = read.regionIds("residenceRegionIds");
		Set<Long> work = read.regionIds("workRegionIds");
		return libraryCatalog.planSearch(residence, work, options);
	}

	/** An id that is not a positive number cannot name a book: 404 like a missing one. */
	private static long parseId(String raw) {
		try {
			long id = Long.parseLong(raw);
			if (id > 0) {
				return id;
			}
		}
		catch (NumberFormatException ex) {
			// fall through
		}
		throw new BookNotFoundException(-1);
	}

}
