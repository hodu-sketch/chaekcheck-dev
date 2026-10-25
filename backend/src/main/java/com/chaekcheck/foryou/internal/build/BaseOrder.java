package com.chaekcheck.foryou.internal.build;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * R2-4: ① books with a popular score that are also on a recommended list, by score; ② then popular-only books (by
 * score) and listed-only books (by how many libraries hold them [안: the whole service]) one after the other, popular
 * first; ③ up to {@code size} books. A work counts as popular when its score is above 0 [안].
 */
public final class BaseOrder {

	private BaseOrder() {
	}

	/** One place of the base list. */
	public record Entry(long bookId, boolean fromPopular) {
	}

	/**
	 * @param scores total score of each work's representative edition
	 * @param listed held-library count of each listed work's representative edition
	 */
	public static List<Entry> order(Map<Long, Double> scores, Map<Long, Integer> listed, int size) {
		Comparator<Long> byScore = Comparator.<Long, Double>comparing(scores::get).reversed()
			.thenComparing(Comparator.naturalOrder());
		Comparator<Long> byHeld = Comparator.<Long, Integer>comparing(listed::get).reversed()
			.thenComparing(Comparator.naturalOrder());
		Set<Long> popular = new HashSet<>();
		scores.forEach((work, score) -> {
			if (score > 0) {
				popular.add(work);
			}
		});
		List<Long> both = popular.stream().filter(listed::containsKey).sorted(byScore).toList();
		List<Long> popularOnly = popular.stream().filter(work -> !listed.containsKey(work)).sorted(byScore).toList();
		List<Long> listedOnly = listed.keySet().stream().filter(work -> !popular.contains(work)).sorted(byHeld).toList();
		List<Entry> entries = new ArrayList<>(size);
		for (long work : both) {
			add(entries, work, true, size);
		}
		for (int i = 0; entries.size() < size && (i < popularOnly.size() || i < listedOnly.size()); i++) {
			if (i < popularOnly.size()) {
				add(entries, popularOnly.get(i), true, size);
			}
			if (i < listedOnly.size()) {
				add(entries, listedOnly.get(i), false, size);
			}
		}
		return entries;
	}

	private static void add(List<Entry> entries, long work, boolean fromPopular, int size) {
		if (entries.size() < size) {
			entries.add(new Entry(work, fromPopular));
		}
	}

}
