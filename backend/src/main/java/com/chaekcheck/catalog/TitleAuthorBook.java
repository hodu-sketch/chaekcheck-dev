package com.chaekcheck.catalog;

/**
 * One of our books found by title and first author ({@link BookLookup#byTitleAndAuthor}).
 * @param query the query it answers, exactly as given
 * @param title {@code catalog.book.title} as stored (see {@link IsbnBook})
 * @param publisherNorm {@code catalog.book.publisher_norm} (see {@link IsbnBook})
 * @param libraryIds {@code catalog.book.library_ids} as stored (see {@link IsbnBook}). A fresh array per call;
 * callers do not change it.
 */
public record TitleAuthorBook(TitleAuthor query, long bookId, String title, String publisherNorm,
		long[] libraryIds) {
}
