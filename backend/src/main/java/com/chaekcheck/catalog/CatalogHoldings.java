package com.chaekcheck.catalog;

import java.util.Collection;
import java.util.List;

/**
 * Public API of the catalog module for the holding stream (ADR-0030): which libraries hold a book, and the detail
 * page of each holding. Holdings of every library are returned; the caller keeps the ones in its plan.
 */
public interface CatalogHoldings {

	/** Holdings of the given books, grouped by book in the given order, then by library id. Unknown ids are ignored. */
	List<HoldingRef> holdingsOf(Collection<Long> bookIds);

	/** Holdings by id, in the given order. Unknown ids are left out. */
	List<HoldingRef> holdings(Collection<Long> holdingIds);

}
