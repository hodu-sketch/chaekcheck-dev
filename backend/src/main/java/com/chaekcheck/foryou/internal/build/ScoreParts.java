package com.chaekcheck.foryou.internal.build;

/**
 * The score of one work and what it is made of (checkpoint C shows them, R2-3a).
 * @param demand on loan + 2 × reservations, summed over the sites and the work's editions
 * @param recent {@code demand} on 0~100 (the largest is 100)
 * @param yearBoost the publish-year boost {@code recent} is multiplied by
 * @param kpipaRank sales rank on 0~100 before its weight; 0 when not ranked
 * @param kpipa200 「화제의 책 200선」 on 0~100 before its weight; 0 when ranked in the sales rank or not listed
 * @param klwave KLWAVE on 0~100 before its weight
 * @param total recent × yearBoost + weighted feeds
 */
public record ScoreParts(long demand, double recent, double yearBoost, double kpipaRank, double kpipa200,
		double klwave, double total) {
}
