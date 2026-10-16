package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;

/**
 * Public API of the catalog module for the recommended lists (ADR-0090, ADR-0124): finds our books by ISBN-13, then by
 * title and first author, and reads books by id. The librarian picks are matched when they are shown; the lists the
 * import carries store the matched book id ({@code picks.list_book.book_id}) and read it back by id.
 * <p>
 * The bean reads {@code catalog}; {@link CatalogLoad#preparedLookup()} gives one that reads the prepared
 * {@code catalog_next}.
 */
public interface BookLookup {

	/**
	 * Our books with the given ISBN-13s, one row per matching book in no particular order. ISBNs without a book are
	 * left out; repeated ISBNs give one row; values that are not an ISBN-13 ({@code ^97[89][0-9]{10}$}) are not
	 * looked up. An empty input gives an empty list. One query for any number of ISBNs.
	 */
	List<IsbnBook> byIsbn13(Collection<String> isbn13s);

	/**
	 * Our books whose title key ({@link com.chaekcheck.catalog.normalize.CatalogText#pickTitleKey}) equals the
	 * query's and that have the query's first author ({@link com.chaekcheck.catalog.normalize.Contributors#firstNameExact})
	 * among their contributors, compared by {@code book_contributor.name_exact}. Every matching book is returned, one
	 * row per query and book in no particular order; choosing one is the caller's job. Queries without a match, with an
	 * empty title key or without a first author are left out; an empty input gives an empty list. Read only, one
	 * query for any number of titles.
	 */
	List<TitleAuthorBook> byTitleAndAuthor(Collection<TitleAuthor> queries);

	/**
	 * Our books with the given ids, one row per book in no particular order. Ids without a book are left out;
	 * repeated ids give one row; nulls are skipped. An empty input gives an empty list. One query for any number of
	 * ids.
	 */
	List<IdBook> byIds(Collection<Long> bookIds);

}
