package com.chaekcheck.foryou.internal.build;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.chaekcheck.foryou.internal.ForYouProperties.Score;

/**
 * R2-3: every material on 0~100 (the largest value is 100; a rank counts as 101 − rank, 201 − rank for the 200 list),
 * recent demand × the publish-year boost, plus the sales rank × 1.5, the 200 list (only books the sales rank lacks)
 * and KLWAVE × 0.3. The e-library ranks themselves are not scored: they only find candidates. Editions are summed
 * into their work's representative edition (decision #6).
 */
public final class Scoring {

	private Scoring() {
	}

	/**
	 * @param rows matched rows of the sources kept today
	 * @param workKeys representative edition of every matched book
	 * @param years publish year of each representative edition (absent = unknown)
	 * @return one entry per representative edition that a kept row reached
	 */
	public static Map<Long, ScoreParts> score(Iterable<MatchedRow> rows, Map<Long, Long> workKeys,
			Map<Long, Integer> years, int buildYear, Score weights) {
		Map<Long, Long> demand = new HashMap<>();
		Map<Long, Integer> salesRank = new HashMap<>();
		Map<Long, Integer> top200 = new HashMap<>();
		Map<Long, Integer> klwave = new HashMap<>();
		Set<Long> works = new HashSet<>();
		for (MatchedRow row : rows) {
			if (row.bookId() == null) {
				continue;
			}
			long work = workKeys.getOrDefault(row.bookId(), row.bookId());
			works.add(work);
			switch (row.source()) {
				case "kyobo_elib", "yes24_elib" -> {
					long loans = row.row().loansNow() == null ? 0 : row.row().loansNow();
					long reservations = row.row().reservations() == null ? 0 : row.row().reservations();
					demand.merge(work, loans + Math.round(weights.reservationWeight() * reservations), Long::sum);
				}
				case "kpipa_rank" -> salesRank.merge(work, row.row().rank(), Math::min);
				case "kpipa_200" -> top200.merge(work, row.row().rank(), Math::min);
				case "klwave" -> klwave.merge(work, row.row().rank(), Math::min);
				default -> throw new IllegalArgumentException("unknown source " + row.source());
			}
		}
		top200.keySet().removeAll(salesRank.keySet());
		long maxDemand = demand.values().stream().mapToLong(Long::longValue).max().orElse(0);
		Map<Long, Double> sales = scaled(salesRank, 101);
		Map<Long, Double> list200 = scaled(top200, 201);
		Map<Long, Double> wave = scaled(klwave, 101);
		Map<Long, ScoreParts> scores = new HashMap<>();
		for (long work : works) {
			long workDemand = demand.getOrDefault(work, 0L);
			double recent = maxDemand == 0 ? 0 : 100.0 * workDemand / maxDemand;
			Integer year = years.get(work);
			double boost = weights.yearBoost(year == null ? null : buildYear - year);
			double s = sales.getOrDefault(work, 0.0);
			double t = list200.getOrDefault(work, 0.0);
			double k = wave.getOrDefault(work, 0.0);
			double total = recent * boost + s * weights.kpipaRankWeight() + t * weights.kpipa200Weight()
					+ k * weights.klwaveWeight();
			scores.put(work, new ScoreParts(workDemand, recent, boost, s, t, k, total));
		}
		return scores;
	}

	/** {@code top + 1 − rank}, the largest made 100. */
	private static Map<Long, Double> scaled(Map<Long, Integer> ranks, int limit) {
		Map<Long, Double> values = new HashMap<>();
		int best = ranks.values().stream().mapToInt(Integer::intValue).min().orElse(limit);
		double max = Math.max(1, limit - best);
		ranks.forEach((work, rank) -> values.put(work, 100.0 * Math.max(0, limit - rank) / max));
		return values;
	}

}
