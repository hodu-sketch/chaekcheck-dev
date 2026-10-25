package com.chaekcheck.aisearch.internal.flow;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reciprocal rank fusion of the keyword and the vector candidates (docs/reports/ai-search-eval-2026-10-25.md, H): a
 * book scores 1 / (k + rank) in each list it is in (rank from 1), the sum orders the mix, ties by keyword rank first,
 * then vector rank, then book id.
 */
final class Fusion {

	private Fusion() {
	}

	static List<Long> rrf(List<Long> keyword, List<Long> vector, int k) {
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
		return ids;
	}

}
