package com.chaekcheck.catalog;

/**
 * One of our books found by title and first author ({@link BookLookup#byTitleAndAuthor}).
 * @param query the query it answers, exactly as given
 * @param libraryIds {@code catalog.book.library_ids} as stored (see {@link IsbnBook}). A fresh array per call;
 * callers do not change it.
 */
public record TitleAuthorBook(TitleAuthor query, long bookId, long[] libraryIds) {
}
