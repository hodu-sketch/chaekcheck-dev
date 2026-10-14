package com.chaekcheck.availability.internal.web;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.chaekcheck.availability.internal.SearchQuery;
import com.chaekcheck.availability.internal.search.LibraryScope;
import com.chaekcheck.availability.internal.search.ResultFilter;
import com.chaekcheck.availability.internal.search.VisitFilter;
import com.chaekcheck.usagelog.SearchConditions;

/**
 * The direct search conditions for the usage log (cycle 15). The next-batch and per-library requests need only the
 * search words (and filter) to run; the web adds {@code scope}, {@code visit}, the regions and {@code libraryIds} so
 * that a "more" row tells which search it was. Those extra values are read leniently: a missing or bad one is left
 * empty in the log and never changes the answer.
 */
final class UsageConditions {

	private UsageConditions() {
	}

	/** The first stream: every value was checked already. */
	static SearchConditions of(SearchQuery query, ResultFilter filter, LibraryScope scope, VisitFilter visit,
			Collection<Long> residence, Collection<Long> work, Collection<Long> libraryIds) {
		return conditions(query, filter.value(), scope.value(), visit.value(), residence, work, libraryIds);
	}

	/** A "more" request: {@code filter} may be null (per-library more), the rest comes from the extra values. */
	static SearchConditions more(SearchQuery query, String filter, String scope, String visit,
			String residenceRegionIds, String workRegionIds, String libraryIds) {
		LibraryScope parsedScope = LibraryScope.parse(scope);
		VisitFilter parsedVisit = VisitFilter.parse(visit);
		return conditions(query, filter, scope == null || parsedScope == null ? null : parsedScope.value(),
				visit == null || parsedVisit == null ? null : parsedVisit.value(),
				ids(() -> RegionIdsParam.parse("residenceRegionIds", residenceRegionIds)),
				ids(() -> RegionIdsParam.parse("workRegionIds", workRegionIds)), ids(() -> LibraryIdsParam.parse(libraryIds)));
	}

	/** {@code filter} of a per-library more: only a known value is kept. */
	static String filterValue(String raw) {
		if (raw == null) {
			return null;
		}
		ResultFilter parsed = ResultFilter.parse(raw);
		return parsed == null ? null : parsed.value();
	}

	private static SearchConditions conditions(SearchQuery query, String filter, String scope, String visit,
			Collection<Long> residence, Collection<Long> work, Collection<Long> libraryIds) {
		String title = null;
		String author = null;
		String publisher = null;
		switch (query.field()) {
			case TITLE -> title = query.value();
			case AUTHOR -> author = query.value();
			case PUBLISHER -> publisher = query.value();
		}
		return SearchConditions.direct(title, author, publisher, filter, scope, visit, List.copyOf(residence),
				List.copyOf(work), List.copyOf(libraryIds));
	}

	private interface IdsReader {

		Set<Long> read();

	}

	private static Set<Long> ids(IdsReader reader) {
		try {
			return reader.read();
		}
		catch (RuntimeException ex) {
			return Set.of();
		}
	}

}
