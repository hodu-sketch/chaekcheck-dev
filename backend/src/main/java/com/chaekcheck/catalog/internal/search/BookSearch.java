package com.chaekcheck.catalog.internal.search;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.chaekcheck.library.SearchTarget;

/**
 * Book search on the collected catalog. Kept behind an interface so the store can change (PGroonga, …) if pg_bigm
 * becomes slow (ADR-0032 "느려지면"). Only holdings of libraries in {@code plan} count; books with none are left
 * out.
 */
public interface BookSearch {

	BookPage search(BookQuery query, List<SearchTarget> plan);

	/** The book with the plan libraries that hold it, in plan order; empty when the book does not exist. */
	Optional<BookDetail> find(long bookId, List<SearchTarget> plan);

	/** A page of books (design-delta API {@code GET /api/v1/books}). */
	record BookPage(List<BookItem> items, int page, int pageSize, long totalCount, boolean hasMore,
			LocalDate collectedFrom, LocalDate collectedTo) {
	}

	record BookItem(long id, String title, String authorDisplay, String publisher, LocalDate publishedOn,
			int holdingLibraryCount, JoinTier joinTier, boolean exactTitle) {
	}

	record BookDetail(long id, String title, String authorDisplay, String publisher, LocalDate publishedOn,
			String isbn13, int holdingLibraryCount, List<LibraryCollected> libraries) {
	}

	/** A plan library that holds the book and when its list was collected ("○월 ○일 기준"). */
	record LibraryCollected(long libraryId, OffsetDateTime collectedAt) {
	}

}
