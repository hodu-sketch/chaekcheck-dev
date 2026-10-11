package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;

/**
 * Public API of the catalog module for the holding stream (ADR-0030): which libraries hold a book, and the detail
 * page of each holding.
 */
public interface CatalogHoldings {

	/**
	 * Holdings of the given books in the given libraries only (the caller's plan, ADR-0066), grouped by book in the
	 * given order, then by library id. Unknown ids are ignored.
	 */
	List<HoldingRef> holdingsOf(Collection<Long> bookIds, Collection<Long> libraryIds);

	/** Holdings by id, in the given order. Unknown ids are left out. */
	List<HoldingRef> holdings(Collection<Long> holdingIds);

}
