package com.chaekcheck.catalog.internal.web;

/** 404 book_not_found. */
final class BookNotFoundException extends RuntimeException {

	BookNotFoundException(long bookId) {
		super("Book not found: " + bookId);
	}

}
