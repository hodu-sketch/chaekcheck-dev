package com.chaekcheck.foryou.internal.build;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.chaekcheck.foryou.internal.ForYouProperties.Score;
import com.chaekcheck.foryou.internal.source.SourceRow;

/** R2-3 (cycle 23 step 5): recent demand, the year boost and its band edges, the feeds and their weights. */
class ScoringTests {

	static final Score WEIGHTS = new Score(2, List.of(3, 6, 10), List.of(1.5, 1.0, 0.6, 0.3), 1.0, 1.5, 1.0, 0.3);

	@Test
	void recentDemandCountsReservationsTwiceAndTheLargestIsHundred() {
		Map<Long, ScoreParts> scores = Scoring.score(List.of(site(1, 3, 1), site(2, 10, 0), site(2, 0, 0)), Map.of(),
				Map.of(1L, 2026, 2L, 2026), 2026, WEIGHTS);

		assertThat(scores.get(1L).demand()).isEqualTo(5);
		assertThat(scores.get(2L).demand()).isEqualTo(10);
		assertThat(scores.get(2L).recent()).isEqualTo(100);
		assertThat(scores.get(1L).recent()).isEqualTo(50);
		assertThat(scores.get(1L).total()).isEqualTo(75); // 50 x 1.5
	}

	@Test
	void yearBandEdges() {
		assertThat(WEIGHTS.yearBoost(0)).isEqualTo(1.5);
		assertThat(WEIGHTS.yearBoost(3)).isEqualTo(1.5);
		assertThat(WEIGHTS.yearBoost(4)).isEqualTo(1.0);
		assertThat(WEIGHTS.yearBoost(6)).isEqualTo(1.0);
		assertThat(WEIGHTS.yearBoost(7)).isEqualTo(0.6);
		assertThat(WEIGHTS.yearBoost(10)).isEqualTo(0.6);
		assertThat(WEIGHTS.yearBoost(11)).isEqualTo(0.3);
		assertThat(WEIGHTS.yearBoost(null)).isEqualTo(1.0);
	}

	@Test
	void editionsAreSummedIntoTheirWork() {
		Map<Long, ScoreParts> scores = Scoring.score(List.of(site(10, 2, 0), site(11, 1, 1)), Map.of(10L, 11L, 11L, 11L),
				Map.of(11L, 2015), 2026, WEIGHTS);

		assertThat(scores).containsOnlyKeys(11L);
		assertThat(scores.get(11L).demand()).isEqualTo(5);
		assertThat(scores.get(11L).total()).isEqualTo(30); // 100 x 0.3
	}

	@Test
	void salesRankWeighsOneAndAHalfAndThe200ListOnlyFillsIn() {
		Map<Long, ScoreParts> scores = Scoring.score(List.of(feed("kpipa_rank", 1, 1), feed("kpipa_rank", 2, 51),
				feed("kpipa_200", 1, 1), feed("kpipa_200", 3, 1), feed("klwave", 4, 1)), Map.of(), Map.of(), 2026,
				WEIGHTS);

		assertThat(scores.get(1L).kpipaRank()).isEqualTo(100);
		assertThat(scores.get(1L).kpipa200()).isZero(); // already in the sales rank
		assertThat(scores.get(1L).total()).isEqualTo(150);
		assertThat(scores.get(2L).kpipaRank()).isCloseTo(50, within(0.01));
		assertThat(scores.get(3L).total()).isEqualTo(100);
		assertThat(scores.get(4L).total()).isCloseTo(30, within(0.01));
	}

	@Test
	void unmatchedRowsAndNoDemandGiveZero() {
		Map<Long, ScoreParts> scores = Scoring.score(
				List.of(new MatchedRow("kyobo_elib", 1L, null, row(0, 0), null), site(5, 0, 0)), Map.of(), Map.of(),
				2026, WEIGHTS);

		assertThat(scores).containsOnlyKeys(5L);
		assertThat(scores.get(5L).total()).isZero();
	}

	static MatchedRow site(long bookId, int loans, int reservations) {
		return new MatchedRow("yes24_elib", 7L, null, row(loans, reservations), bookId);
	}

	static MatchedRow feed(String source, long bookId, int rank) {
		return new MatchedRow(source, null, "week", new SourceRow(rank, "책", null, null, null, null, null, null), bookId);
	}

	static SourceRow row(int loans, int reservations) {
		return new SourceRow(1, "책", null, null, null, "k", loans, reservations);
	}

}
