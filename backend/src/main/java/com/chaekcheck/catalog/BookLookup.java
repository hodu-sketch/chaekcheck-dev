package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;

/**
 * Public API of the catalog module for the popular books (ADR-0074): finds our books by ISBN-13, so a ranking book
 * and our book are matched when they are shown, never stored as a pair. Titles are not matched.
 */
public interface BookLookup {

	/**
	 * Our books with the given ISBN-13s, one row per matching book in no particular order. ISBNs without a book are
	 * left out; repeated ISBNs give one row; values that are not an ISBN-13 ({@code ^97[89][0-9]{10}$}) are not
	 * looked up. An empty input gives an empty list. One query for any number of ISBNs.
	 */
	List<IsbnBook> byIsbn13(Collection<String> isbn13s);

}
