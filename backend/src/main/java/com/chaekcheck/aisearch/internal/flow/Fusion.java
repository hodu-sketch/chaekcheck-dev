package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.function.ToDoubleFunction;

/**
 * Reciprocal rank fusion of the keyword and the vector candidates (docs/features/aisearch/reports/2026-10-25-eval.md, H): a
 * book scores 1 / (k + rank) in each list it is in (rank from 1), the sum orders the mix, ties by keyword rank first,
 * then vector rank, then book id. The ML first call adds a shelf bonus to the sum and puts the books the question rules
 * out at the end (PFx, docs/features/aisearch/reports/2026-10-25-improvement.md 6-4).
 */
final class Fusion {

	private Fusion() {
	}

	static List<Long> rrf(List<Long> keyword, List<Long> vector, int k) {
		return rrf(keyword, vector, k, id -> 0, id -> false);
	}

	/**
	 * @param bonus added to a book's sum
	 * @param last the books that go after all the others, in the same order among themselves
	 */
	static List<Long> rrf(List<Long> keyword, List<Long> vector, int k, ToDoubleFunction<Long> bonus,
			Predicate<Long> last) {
		Map<Long, Double> score = new LinkedHashMap<>();
		Map<Long, Integer> keywordRank = new HashMap<>();
		Map<Long, Integer> vectorRank = new HashMap<>();
		for (int i = 0; i < keyword.size(); i++) {
			if (keywordRank.putIfAbsent(keyword.get(i), i) == null) {
				score.merge(keyword.get(i), 1.0 / (k + i + 1), Double::sum);
			}
		}
		for (int i = 0; i < vector.size(); i++) {
			if (vectorRank.putIfAbsent(vector.get(i), i) == null) {
				score.merge(vector.get(i), 1.0 / (k + i + 1), Double::sum);
			}
		}
		score.replaceAll((id, sum) -> sum + bonus.applyAsDouble(id));
		List<Long> ids = new ArrayList<>(score.keySet());
		ids.sort((a, b) -> {
			int bySum = Double.compare(score.get(b), score.get(a));
			if (bySum != 0) {
				return bySum;
			}
			int byKeyword = Integer.compare(keywordRank.getOrDefault(a, Integer.MAX_VALUE),
					keywordRank.getOrDefault(b, Integer.MAX_VALUE));
			if (byKeyword != 0) {
				return byKeyword;
			}
			int byVector = Integer.compare(vectorRank.getOrDefault(a, Integer.MAX_VALUE),
					vectorRank.getOrDefault(b, Integer.MAX_VALUE));
			return byVector != 0 ? byVector : Long.compare(a, b);
		});
		List<Long> ordered = new ArrayList<>(ids.stream().filter(id -> !last.test(id)).toList());
		ordered.addAll(ids.stream().filter(last).toList());
		return ordered;
	}

}
