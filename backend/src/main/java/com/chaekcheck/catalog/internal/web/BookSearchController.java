package com.chaekcheck.catalog.internal.web;

import java.util.List;
import java.util.Set;

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
import com.chaekcheck.library.LibraryCatalog;
import com.chaekcheck.library.PlanOptions;
import com.chaekcheck.library.SearchTarget;

/**
 * Book search on the collected catalog (ADR-0030, design-delta API). No library site is requested here; the holding
 * stream in the availability module does that.
 */
@RestController
class BookSearchController {

	private final BookSearch bookSearch;

	private final LibraryCatalog libraryCatalog;

	BookSearchController(BookSearch bookSearch, LibraryCatalog libraryCatalog) {
		this.bookSearch = bookSearch;
		this.libraryCatalog = libraryCatalog;
	}

	@GetMapping("/api/v1/books")
	BookPage books(@RequestParam MultiValueMap<String, String> params) {
		BookParams read = new BookParams(params);
		BookQuery query = read.query();
		return bookSearch.search(query, plan(read));
	}

	@GetMapping("/api/v1/books/{bookId}")
	BookDetail book(@PathVariable String bookId, @RequestParam MultiValueMap<String, String> params) {
		long id = parseId(bookId);
		List<SearchTarget> plan = plan(new BookParams(params == null ? new LinkedMultiValueMap<>() : params));
		return bookSearch.find(id, plan).orElseThrow(() -> new BookNotFoundException(id));
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
