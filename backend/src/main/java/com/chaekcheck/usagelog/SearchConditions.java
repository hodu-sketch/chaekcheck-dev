package com.chaekcheck.usagelog;

import java.util.Collection;
import java.util.List;

/**
 * The search conditions as the server read them (spec R1-2): trimmed search words, then the method values of the
 * search kind, then where to search. {@code exact}, {@code roles} and {@code sort} belong to the catalog search,
 * {@code filter} to the direct search; the others are null. Lists are never null.
 * @param exact fields searched exactly ({@code title}, {@code author}, {@code publisher}), empty when none
 * @param roles extra author roles asked for ({@code translator}, ...), empty when none
 * @param sort {@code default}, {@code newest} or {@code oldest}
 * @param filter {@code all} or {@code available}
 * @param scope {@code all} or {@code joinable}; null only when a "more" request did not carry it
 * @param visit {@code any} or {@code none}; null only when a "more" request did not carry it
 */
public record SearchConditions(String title, String author, String publisher, List<String> exact, List<String> roles,
		String sort, String filter, String scope, String visit, List<Long> residenceRegionIds,
		List<Long> workRegionIds, List<Long> libraryIds) {

	public SearchConditions {
		exact = exact == null ? null : List.copyOf(exact);
		roles = roles == null ? null : List.copyOf(roles);
		residenceRegionIds = copy(residenceRegionIds);
		workRegionIds = copy(workRegionIds);
		libraryIds = copy(libraryIds);
	}

	/** A catalog search ({@code GET /api/v1/books}). */
	public static SearchConditions catalog(String title, String author, String publisher, Collection<String> exact,
			Collection<String> roles, String sort, String scope, String visit, Collection<Long> residenceRegionIds,
			Collection<Long> workRegionIds, Collection<Long> libraryIds) {
		return new SearchConditions(title, author, publisher, List.copyOf(exact), List.copyOf(roles), sort, null, scope,
				visit, copy(residenceRegionIds), copy(workRegionIds), copy(libraryIds));
	}

	/** A direct search (library sites). {@code filter} may be null for a per-library "more" that did not carry it. */
	public static SearchConditions direct(String title, String author, String publisher, String filter, String scope,
			String visit, Collection<Long> residenceRegionIds, Collection<Long> workRegionIds,
			Collection<Long> libraryIds) {
		return new SearchConditions(title, author, publisher, null, null, null, filter, scope, visit,
				copy(residenceRegionIds), copy(workRegionIds), copy(libraryIds));
	}

	private static List<Long> copy(Collection<Long> ids) {
		return ids == null ? List.of() : List.copyOf(ids);
	}

}
