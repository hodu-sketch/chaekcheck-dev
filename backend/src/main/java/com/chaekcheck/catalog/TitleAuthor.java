package com.chaekcheck.catalog;

/**
 * A book to find by title and first author ({@link BookLookup#byTitleAndAuthor}), as the source wrote it: the catalog
 * makes the title key and the first author itself.
 */
public record TitleAuthor(String title, String author) {
}
