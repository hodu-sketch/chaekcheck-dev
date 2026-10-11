package com.chaekcheck.catalog;

/**
 * One of our books found by ISBN ({@link BookLookup#byIsbn13}).
 * @param libraryIds {@code catalog.book.library_ids} as stored: may hold libraries that are disabled now, so the
 * caller checks which of them are in its plan. A fresh array per call; callers do not change it.
 */
public record IsbnBook(String isbn13, long bookId, long[] libraryIds) {
}
