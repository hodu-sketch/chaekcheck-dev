package com.chaekcheck.foryou.internal;

import java.time.Duration;
import java.time.ZoneId;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The for-you tab's settings (cycle 23; docs/maintenance/settings-index.md). Values marked [안] in application.yml
 * are proposals; the score weights are tuned at checkpoint C (R2-3a).
 */
@ConfigurationProperties("chaekcheck.foryou")
public record ForYouProperties(Build build, Sources sources, Score score) {

	/**
	 * @param enabled false = no start-up build and no daily build (tests, the import profile)
	 * @param cron the daily build, Spring cron
	 * @param keep build runs and their rows older than this are deleted, except the last succeeded run [확정 30일]
	 * @param baseSize books in the base list (R2-4) [확정 40]
	 * @param refreshInterval the memory copy reads the last succeeded build again this often (like PicksCache)
	 * @param minMatchRate a site or source whose rows match our books less than this is left out for the day
	 *     (to-dev-popular-list-check.md 2) [확정 0.9]
	 */
	public record Build(boolean enabled, String cron, ZoneId zone, Duration keep, int baseSize,
			Duration refreshInterval, double minMatchRate) {
	}

	/** One switch per source (ADR-0147). */
	public record Sources(Sites kyoboElib, Sites yes24Elib, Feed kpipaRank, Feed kpipa200, Feed klwave) {
	}

	/**
	 * @param libraryIds the e-library sites ({@code library.id}), read in this order
	 * @param top rows read per site [확정 100]
	 */
	public record Sites(boolean enabled, List<Long> libraryIds, int top) {

		public Sites {
			libraryIds = libraryIds == null ? List.of() : List.copyOf(libraryIds);
		}

	}

	/** A source whose shape is not known yet (step 0 #7): it stays off until the user checks it on the PC. */
	public record Feed(boolean enabled) {
	}

	/**
	 * @param reservationWeight recent demand = on loan + this × reservations [확정 2]
	 * @param yearBands publish-year distance limits, oldest band last [확정 3, 6, 10]
	 * @param yearBoosts one more than the bands: within each band, then beyond the last [확정 1.5, 1.0, 0.6, 0.3]
	 * @param unknownYearBoost a book without a publish year [안 1.0]
	 * @param kpipaRankWeight [확정 1.5]
	 * @param kpipa200Weight books of the 200 not in the sales rank [안 1.0]
	 * @param klwaveWeight [안 0.3]
	 */
	public record Score(double reservationWeight, List<Integer> yearBands, List<Double> yearBoosts,
			double unknownYearBoost, double kpipaRankWeight, double kpipa200Weight, double klwaveWeight) {

		public Score {
			yearBands = List.copyOf(yearBands);
			yearBoosts = List.copyOf(yearBoosts);
			if (yearBoosts.size() != yearBands.size() + 1) {
				throw new IllegalArgumentException("year-boosts needs one more value than year-bands");
			}
		}

		/** The boost for a book published {@code age} years before the build year, or null when unknown. */
		public double yearBoost(Integer age) {
			if (age == null) {
				return unknownYearBoost;
			}
			for (int i = 0; i < yearBands.size(); i++) {
				if (age <= yearBands.get(i)) {
					return yearBoosts.get(i);
				}
			}
			return yearBoosts.getLast();
		}

	}

}
