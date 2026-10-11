package com.chaekcheck.popular.internal.fetch;

import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import com.chaekcheck.popular.internal.PopularCategory;

/**
 * The order lists are shown in (spec R2-2, R2-3 [확정]): "전체" 0, then the 14 categories by sales count, largest
 * first, equal counts by site id, 1 to 14.
 */
public final class CategoryOrder {

	private CategoryOrder() {
	}

	/** @param salesCopies the count of every category but "전체" */
	public static Map<PopularCategory, Integer> of(Map<PopularCategory, Long> salesCopies) {
		Map<PopularCategory, Integer> order = new EnumMap<>(PopularCategory.class);
		order.put(PopularCategory.ALL, 0);
		List<PopularCategory> sorted = PopularCategory.withSales()
			.stream()
			.sorted(Comparator.comparingLong((PopularCategory category) -> salesCopies.get(category))
				.reversed()
				.thenComparing(PopularCategory::siteId))
			.toList();
		for (int i = 0; i < sorted.size(); i++) {
			order.put(sorted.get(i), i + 1);
		}
		return order;
	}

}
