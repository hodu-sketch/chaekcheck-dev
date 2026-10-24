package com.chaekcheck.foryou.internal.listed;

import java.util.List;

/**
 * The books on the recommended lists, for the base list's order (R2-4) and the card footers (R3-2).
 * <p>
 * TODO(cycle 24 merge): picks' {@code ListedBooks.current()} (cycle 24 design, ADR-0150/0151) is not on main yet
 * (user 2026-10-24: "24가 main에 들어갈 때까지 기다림"). Once it is, one adapter over it replaces {@link #NONE}.
 */
public interface ListedSource {

	ListedSource NONE = List::of;

	List<ListedRef> listed();

	/**
	 * @param listKeys the lists the book is on ({@code librarian}, {@code kaist}, …)
	 * @param libraryIds {@code catalog.book.library_ids} of the book
	 */
	record ListedRef(long bookId, List<String> listKeys, long[] libraryIds) {
	}

}
