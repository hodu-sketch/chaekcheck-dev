package com.chaekcheck.catalog;

/**
 * One of our books found by ISBN ({@link BookLookup#byIsbn13}).
 * @param title {@code catalog.book.title} as stored, for the shown name (ADR-0129)
 * @param publisherNorm {@code catalog.book.publisher_norm}
 * ({@link com.chaekcheck.catalog.normalize.CatalogText#publisherNorm}), for narrowing by publisher
 * @param libraryIds {@code catalog.book.library_ids} as stored: may hold libraries that are disabled now, so the
 * caller checks which of them are in its plan. A fresh array per call; callers do not change it.
 */
public record IsbnBook(String isbn13, long bookId, String title, String publisherNorm, long[] libraryIds) {
}
